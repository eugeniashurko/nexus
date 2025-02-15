package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{ResolverContextResolution, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{ProjectSetup, ResolversDummy}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import io.circe.literal._
import monix.bio.IO
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.util.UUID

class ResolverReferenceExchangeSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with IOValues
    with IOFixedClock
    with Inspectors
    with TestHelpers {

  implicit private val scheduler: Scheduler = Scheduler.global

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val caller: Caller   = Caller(subject, Set(subject))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))
  private val uuid                      = UUID.randomUUID()
  implicit private val uuidF: UUIDF     = UUIDF.fixed(uuid)

  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixed(
      contexts.metadata          -> jsonContentOf("contexts/metadata.json").topContextValueOrEmpty,
      contexts.resolvers         -> jsonContentOf("contexts/resolvers.json").topContextValueOrEmpty,
      contexts.resolversMetadata -> jsonContentOf("/contexts/resolvers-metadata.json").topContextValueOrEmpty
    )

  private val org     = Label.unsafe("myorg")
  private val project = ProjectGen.project("myorg", "myproject", base = nxv.base)

  private val (orgs, projs) = ProjectSetup
    .init(
      orgsToCreate = org :: Nil,
      projectsToCreate = project :: Nil
    )
    .accepted

  private val resolverContextResolution: ResolverContextResolution =
    new ResolverContextResolution(res, (_, _, _) => IO.raiseError(ResourceResolutionReport()))

  private val resolvers: Resolvers =
    ResolversDummy(orgs, projs, resolverContextResolution, (_, _) => IO.unit).accepted

  "A ResolverReferenceExchange" should {
    val id      = iri"http://localhost/${genString()}"
    val source  =
      json"""{
              "@type": ["InProject", "Resolver"],
              "priority": 42
            }"""
    val tag     = TagLabel.unsafe("tag")
    val resRev1 = resolvers.create(id, project.ref, source).accepted
    val resRev2 = resolvers.tag(id, project.ref, tag, 1L, 1L).accepted

    val exchange = Resolvers.referenceExchange(resolvers)

    "return a resolver by id" in {
      val value = exchange.fetch(project.ref, Latest(id)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev2
    }

    "return a resolver by tag" in {
      val value = exchange.fetch(project.ref, Tag(id, tag)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return a resolver by rev" in {
      val value = exchange.fetch(project.ref, Revision(id, 1L)).accepted.value
      value.source shouldEqual source
      value.resource shouldEqual resRev1
    }

    "return None for incorrect id" in {
      exchange.fetch(project.ref, Latest(iri"http://localhost/${genString()}")).accepted shouldEqual None
    }

    "return None for incorrect revision" in {
      exchange.fetch(project.ref, Revision(id, 1000L)).accepted shouldEqual None
    }

    "return None for incorrect tag" in {
      val label = TagLabel.unsafe("unknown")
      exchange.fetch(project.ref, Tag(id, label)).accepted shouldEqual None
    }

  }
}
