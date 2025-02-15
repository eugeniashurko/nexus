package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics.filterMetadataKeys
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import io.circe.Json
import monix.bio.Task
import org.scalatest.Assertion

class DiskStorageSpec extends StorageSpec {

  override def storageName: String = "disk"

  override def storageType: String = "DiskStorage"

  override def storageId: String = "mystorage"

  override def locationPrefix: Option[String] = None

  override def createStorages: Task[Assertion] = {
    val payload  = jsonContentOf("/kg/storages/disk.json")
    val payload2 = jsonContentOf("/kg/storages/disk-perms.json")

    for {
      _ <- deltaClient.post[Json](s"/storages/$fullId", payload, Coyote) { (_, response) =>
             response.status shouldEqual StatusCodes.Created
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:$storageId", Coyote) { (json, response) =>
             val expected = jsonContentOf(
               "/kg/storages/disk-response.json",
               replacements(
                 Coyote,
                 "id"          -> storageId,
                 "project"     -> fullId,
                 "read"        -> "resources/read",
                 "maxFileSize" -> storageConfig.maxFileSize.toString,
                 "write"       -> "files/write"
               ): _*
             )
             filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
             response.status shouldEqual StatusCodes.OK
           }
      _ <- permissionDsl.addPermissions(
             Permission(storageName, "read"),
             Permission(storageName, "write")
           )
      _ <- deltaClient.post[Json](s"/storages/$fullId", payload2, Coyote) { (_, response) =>
             response.status shouldEqual StatusCodes.Created
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:${storageId}2", Coyote) { (json, response) =>
             val expected = jsonContentOf(
               "/kg/storages/disk-response.json",
               replacements(
                 Coyote,
                 "id"          -> s"${storageId}2",
                 "project"     -> fullId,
                 "read"        -> s"$storageName/read",
                 "maxFileSize" -> storageConfig.maxFileSize.toString,
                 "write"       -> s"$storageName/write"
               ): _*
             )
             filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
             response.status shouldEqual StatusCodes.OK
           }
    } yield succeed
  }

  "creating a disk storage" should {
    "fail creating a DiskStorage on a wrong volume" in {
      val volume  = "/" + genString()
      val payload = jsonContentOf("/kg/storages/disk.json") deepMerge
        Json.obj(
          "@id"    -> Json.fromString("https://bluebrain.github.io/nexus/vocabulary/invalid-volume"),
          "volume" -> Json.fromString(volume)
        )

      deltaClient.post[Json](s"/storages/$fullId", payload, Coyote) { (json, response) =>
        json shouldEqual jsonContentOf("/kg/storages/error.json", "volume" -> volume)
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  s"Linking against the default storage" should {
    "reject linking operations" in {
      val payload = Json.obj(
        "filename"  -> Json.fromString("logo.png"),
        "path"      -> Json.fromString("does/not/matter"),
        "mediaType" -> Json.fromString("image/png")
      )

      deltaClient.put[Json](s"/files/$fullId/linking.png", payload, Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf("/kg/files/linking-notsupported.json", "org" -> orgId, "proj" -> projId)
      }
    }
  }
}
