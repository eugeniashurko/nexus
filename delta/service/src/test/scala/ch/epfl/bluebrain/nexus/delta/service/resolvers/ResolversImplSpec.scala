package ch.epfl.bluebrain.nexus.delta.service.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverEvent
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures, ResolversBehaviors}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.{IO, Task}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class ResolversImplSpec
    extends AbstractDBSpec
    with ResolversBehaviors
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with OptionValues
    with Inspectors
    with CirceLiteral
    with ConfigFixtures {

  private val resolversConfig = ResolversConfig(aggregate, keyValueStore, pagination, cacheIndexing)

  override def create: Task[Resolvers] =
    EventLog
      .postgresEventLog[Envelope[ResolverEvent]](EventLogUtils.toEnvelope)
      .hideErrors
      .flatMap(
        ResolversImpl(resolversConfig, _, orgs, projects, resolverContextResolution, (_, _) => IO.unit)
      )
}
