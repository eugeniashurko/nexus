package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import ch.epfl.bluebrain.nexus.tests.Identity.events.BugsBunny
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.{Events, Organizations, Resources}
import ch.epfl.bluebrain.nexus.tests.kg.VersionSpec.VersionBundle
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity}
import com.fasterxml.uuid.Generators
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inspectors

class EventsSpec extends BaseSpec with Inspectors {

  private val orgId                          = genId()
  private val orgId2                         = genId()
  private val projId                         = genId()
  private val id                             = s"$orgId/$projId"
  private val id2                            = s"$orgId2/$projId"
  private var initialEventId: Option[String] = None

  override def beforeAll(): Unit = {
    super.beforeAll()
    deltaClient
      .get[VersionBundle]("/version", Identity.ServiceAccount) { (version, _) =>
        initialEventId = version.dependencies.cassandra.map(_ => Generators.timeBasedGenerator().generate().toString)
        succeed
      }
      .void
      .runSyncUnsafe()
  }

  "creating projects" should {

    "add necessary permissions for user" in {
      aclDsl.addPermissions(
        "/",
        BugsBunny,
        Set(Organizations.Create, Events.Read, Resources.Read)
      )
    }

    "succeed creating project 1 if payload is correct" in {
      for {
        _ <- adminDsl.createOrganization(orgId, orgId, BugsBunny)
        _ <- adminDsl.createProject(orgId, projId, kgDsl.projectJson(name = id), BugsBunny)
      } yield succeed
    }

    "succeed creating project 2 if payload is correct" in {
      for {
        _ <- adminDsl.createOrganization(orgId2, orgId2, BugsBunny)
        _ <- adminDsl.createProject(orgId2, projId, kgDsl.projectJson(name = id2), BugsBunny)
      } yield succeed
    }

    "wait for default project events" in {
      val endpoints = List(
        s"/views/$id/nxv:defaultElasticSearchIndex",
        s"/views/$id/nxv:defaultSparqlIndex",
        s"/resolvers/$id/nxv:defaultInProject",
        s"/storages/$id/nxv:diskStorageDefault",
        s"/views/$id2/nxv:defaultElasticSearchIndex",
        s"/views/$id2/nxv:defaultSparqlIndex",
        s"/resolvers/$id2/nxv:defaultInProject",
        s"/storages/$id2/nxv:diskStorageDefault"
      )

      forAll(endpoints) { endpoint =>
        eventually {
          deltaClient.get[Json](endpoint, BugsBunny) { (_, response) =>
            response.status shouldEqual StatusCodes.OK
          }
        }
      }
    }

    "wait for storages to be indexed" in {
      val endpoints = List(
        s"/storages/$id",
        s"/storages/$id2"
      )

      forAll(endpoints) { endpoint =>
        eventually {
          deltaClient.get[Json](endpoint, BugsBunny) { (json, response) =>
            response.status shouldEqual StatusCodes.OK
            _total.getOption(json).value shouldEqual 1
          }
        }
      }
    }
  }

  "fetching events" should {

    "add events to project" in {
      //Created event
      val payload = jsonContentOf(
        "/kg/resources/simple-resource.json",
        "priority"   -> "3",
        "resourceId" -> "1"
      )

      for {
        //ResourceCreated event
        _ <- deltaClient.put[Json](s"/resources/$id/_/test-resource:1", payload, BugsBunny) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        _ <- deltaClient.put[Json](s"/resources/$id2/_/test-resource:1", payload, BugsBunny) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        //ResourceUpdated event
        _ <- deltaClient.put[Json](
               s"/resources/$id/_/test-resource:1?rev=1",
               jsonContentOf(
                 "/kg/resources/simple-resource.json",
                 "priority"   -> "5",
                 "resourceId" -> "1"
               ),
               BugsBunny
             ) { (_, response) =>
               response.status shouldEqual StatusCodes.OK
             }
        //ResourceTagAdded event
        _ <- deltaClient.post[Json](
               s"/resources/$id/_/test-resource:1/tags?rev=2",
               jsonContentOf(
                 "/kg/resources/tag.json",
                 "tag" -> "v1.0.0",
                 "rev" -> "1"
               ),
               BugsBunny
             ) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        // ResourceDeprecated event
        _ <- deltaClient.delete[Json](s"/resources/$id/_/test-resource:1?rev=3", BugsBunny) { (_, response) =>
               response.status shouldEqual StatusCodes.OK
             }
        //FileCreated event
        _ <- deltaClient.putAttachment[Json](
               s"/files/$id/attachment.json",
               contentOf("/kg/files/attachment.json"),
               ContentTypes.`application/json`,
               "attachment.json",
               BugsBunny
             ) { (_, response) =>
               response.status shouldEqual StatusCodes.Created
             }
        //FileUpdated event
        _ <- deltaClient.putAttachment[Json](
               s"/files/$id/attachment.json?rev=1",
               contentOf("/kg/files/attachment2.json"),
               ContentTypes.`application/json`,
               "attachment.json",
               BugsBunny
             ) { (_, response) =>
               response.status shouldEqual StatusCodes.OK
             }
      } yield succeed
    }

    "fetch resource events filtered by project" in {
      for {
        uuids <- adminDsl.getUuids(orgId, projId, BugsBunny)
        _     <- deltaClient.sseEvents(s"/resources/$id/events", BugsBunny, initialEventId, take = 12L) { seq =>
                   val projectEvents = seq.drop(6)
                   projectEvents.size shouldEqual 6
                   projectEvents.flatMap(_._1) should contain theSameElementsInOrderAs List(
                     "ResourceCreated",
                     "ResourceUpdated",
                     "ResourceTagAdded",
                     "ResourceDeprecated",
                     "FileCreated",
                     "FileUpdated"
                   )
                   val json          = Json.arr(projectEvents.flatMap(_._2.map(events.filterFields)): _*)
                   json shouldEqual jsonContentOf(
                     "/kg/events/events.json",
                     replacements(
                       BugsBunny,
                       "resources"        -> s"${config.deltaUri}/resources/$id",
                       "organizationUuid" -> uuids._1,
                       "projectUuid"      -> uuids._2,
                       "project"          -> s"${config.deltaUri}/projects/$orgId/$projId",
                       "schemaProject"    -> s"${config.deltaUri}/projects/$orgId/$projId"
                     ): _*
                   )
                 }
      } yield succeed
    }

    "fetch resource events filtered by organization 1" in {
      for {
        uuids <- adminDsl.getUuids(orgId, projId, BugsBunny)
        _     <- deltaClient.sseEvents(s"/resources/$orgId/events", BugsBunny, initialEventId, take = 13L) { seq =>
                   val projectEvents = seq.drop(7)
                   projectEvents.size shouldEqual 6
                   projectEvents.flatMap(_._1) should contain theSameElementsInOrderAs List(
                     "ResourceCreated",
                     "ResourceUpdated",
                     "ResourceTagAdded",
                     "ResourceDeprecated",
                     "FileCreated",
                     "FileUpdated"
                   )
                   val json          = Json.arr(projectEvents.flatMap(_._2.map(events.filterFields)): _*)
                   json shouldEqual jsonContentOf(
                     "/kg/events/events.json",
                     replacements(
                       BugsBunny,
                       "resources"        -> s"${config.deltaUri}/resources/$id",
                       "organizationUuid" -> uuids._1,
                       "projectUuid"      -> uuids._2,
                       "project"          -> s"${config.deltaUri}/projects/$orgId/$projId",
                       "schemaProject"    -> s"${config.deltaUri}/projects/$orgId/$projId"
                     ): _*
                   )
                 }
      } yield succeed
    }

    "fetch resource events filtered by organization 2" in {
      for {
        uuids <- adminDsl.getUuids(orgId2, projId, BugsBunny)
        _     <-
          deltaClient.sseEvents(s"/resources/$orgId2/events", BugsBunny, initialEventId, take = 8L) { seq =>
            val projectEvents = seq.drop(7)
            projectEvents.size shouldEqual 1
            projectEvents.flatMap(_._1) should contain theSameElementsInOrderAs List("ResourceCreated")
            val json          = Json.arr(projectEvents.flatMap(_._2.map(events.filterFields)): _*)
            json shouldEqual jsonContentOf(
              "/kg/events/events2.json",
              replacements(
                BugsBunny,
                "resources"        -> s"${config.deltaUri}/resources/$id",
                "organizationUuid" -> uuids._1,
                "projectUuid"      -> uuids._2,
                "project"          -> s"${config.deltaUri}/projects/$orgId2/$projId",
                "schemaProject"    -> s"${config.deltaUri}/projects/$orgId2/$projId"
              ): _*
            )
          }
      } yield succeed
    }

    "fetch global events" in {
      // Only for cassandra, it is difficult to get the current sequence value with PostgreSQL
      Task
        .when(initialEventId.isDefined) {
          for {
            uuids  <- adminDsl.getUuids(orgId, projId, BugsBunny)
            uuids2 <- adminDsl.getUuids(orgId2, projId, BugsBunny)
            _      <- deltaClient.sseEvents(s"/resources/events", BugsBunny, initialEventId, take = 19) { seq =>
                        val projectEvents = seq.drop(12)
                        projectEvents.size shouldEqual 7
                        projectEvents.flatMap(_._1) should contain theSameElementsInOrderAs List(
                          "ResourceCreated",
                          "ResourceCreated",
                          "ResourceUpdated",
                          "ResourceTagAdded",
                          "ResourceDeprecated",
                          "FileCreated",
                          "FileUpdated"
                        )
                        val json          = Json.arr(projectEvents.flatMap(_._2.map(events.filterFields)): _*)
                        json shouldEqual jsonContentOf(
                          "/kg/events/events-multi-project.json",
                          replacements(
                            BugsBunny,
                            "resources"         -> s"${config.deltaUri}/resources/$id",
                            "organizationUuid"  -> uuids._1,
                            "projectUuid"       -> uuids._2,
                            "organization2Uuid" -> uuids2._1,
                            "project2Uuid"      -> uuids2._2,
                            "project"           -> s"${config.deltaUri}/projects/$orgId/$projId",
                            "project2"          -> s"${config.deltaUri}/projects/$orgId2/$projId",
                            "schemaProject"     -> s"${config.deltaUri}/projects/$orgId/$projId",
                            "schemaProject2"    -> s"${config.deltaUri}/projects/$orgId2/$projId"
                          ): _*
                        )
                      }
          } yield ()
        }
        .as(succeed)
    }
  }
}
