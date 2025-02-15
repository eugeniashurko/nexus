package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.BootstrapSetup
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.stream.{Materializer, SystemMaterializer}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.service.utils.OwnerPermissionsScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, DatabaseFlavour}
import ch.epfl.bluebrain.nexus.delta.sourcing.persistenceid.PersistenceIdCheck
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import io.circe.{Decoder, Encoder}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}

/**
  * Complete service wiring definitions.
  *
  * @param appCfg      the application configuration
  * @param config      the raw merged and resolved configuration
  */
class DeltaModule(appCfg: AppConfig, config: Config)(implicit classLoader: ClassLoader) extends ModuleDef {

  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseConfig].from { appCfg.database }
  make[ProjectsConfig].from { appCfg.projects }
  make[DatabaseFlavour].from { appCfg.database.flavour }
  make[BaseUri].from { appCfg.http.baseUri }
  make[ServiceAccount].from { appCfg.serviceAccount.value }
  make[Crypto].from { appCfg.encryption.crypto }

  make[List[PluginDescription]].from { (pluginsDef: List[PluginDef]) => pluginsDef.map(_.info) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/metadata.json"))

  make[IndexingAction].named("aggregate").from { (internal: Set[IndexingAction]) =>
    AggregateIndexingAction(internal.toSeq)
  }
  make[RemoteContextResolution].named("aggregate").fromEffect { (otherCtxResolutions: Set[RemoteContextResolution]) =>
    for {
      errorCtx    <- ContextValue.fromFile("contexts/error.json")
      metadataCtx <- ContextValue.fromFile("contexts/metadata.json")
      searchCtx   <- ContextValue.fromFile("contexts/search.json")
      tagsCtx     <- ContextValue.fromFile("contexts/tags.json")
      versionCtx  <- ContextValue.fromFile("contexts/version.json")
    } yield RemoteContextResolution
      .fixed(
        contexts.error    -> errorCtx,
        contexts.metadata -> metadataCtx,
        contexts.search   -> searchCtx,
        contexts.tags     -> tagsCtx,
        contexts.version  -> versionCtx
      )
      .merge(otherCtxResolutions.toSeq: _*)
  }

  make[Clock[UIO]].from(Clock[UIO])
  make[UUIDF].from(UUIDF.random)
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )
  )
  make[ActorSystem[Nothing]].from(
    ActorSystem[Nothing](
      Behaviors.empty,
      appCfg.description.fullName,
      BootstrapSetup().withConfig(config).withClassloader(classLoader)
    )
  )
  make[Materializer].from((as: ActorSystem[Nothing]) => SystemMaterializer(as).materializer)
  make[Logger].from { LoggerFactory.getLogger("delta") }
  make[RejectionHandler].from {
    (s: Scheduler, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering) =>
      RdfRejectionHandler(s, cr, ordering)
  }
  make[ExceptionHandler].from {
    (s: Scheduler, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering, base: BaseUri) =>
      RdfExceptionHandler(s, cr, ordering, base)
  }
  make[CorsSettings].from(
    CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
  )

  make[EventLog[Envelope[Event]]].fromEffect { databaseEventLog[Event](_, _) }
  make[EventLog[Envelope[ProjectScopedEvent]]].fromEffect { databaseEventLog[ProjectScopedEvent](_, _) }

  make[Projection[ProjectCountsCollection]].fromEffect { (system: ActorSystem[Nothing], clock: Clock[UIO]) =>
    projection(ProjectCountsCollection.empty, system, clock)
  }

  make[Projection[Unit]].fromEffect { (system: ActorSystem[Nothing], clock: Clock[UIO]) =>
    projection((), system, clock)
  }

  make[ProjectsCounts].fromEffect {
    (
        projection: Projection[ProjectCountsCollection],
        eventLog: EventLog[Envelope[ProjectScopedEvent]],
        uuidF: UUIDF,
        as: ActorSystem[Nothing],
        sc: Scheduler
    ) =>
      ProjectsCounts(appCfg.projects, projection, eventLog.eventsByTag(Event.eventTag, _))(uuidF, as, sc)
  }

  many[ScopeInitialization].add { (acls: Acls, serviceAccount: ServiceAccount) =>
    new OwnerPermissionsScopeInitialization(acls, appCfg.permissions.ownerPermissions, serviceAccount)
  }

  make[Vector[Route]].from { (pluginsRoutes: Set[PriorityRoute]) =>
    pluginsRoutes.toVector.sorted.map(_.route)
  }

  make[PersistenceIdCheck].fromEffect { (config: DatabaseConfig, system: ActorSystem[Nothing]) =>
    if (config.verifyIdUniqueness)
      config.flavour match {
        case Postgres  => PersistenceIdCheck.postgres(config.postgres)
        case Cassandra => PersistenceIdCheck.cassandra(config.cassandra)(system)
      }
    else Task.delay(PersistenceIdCheck.skipPersistenceIdCheck)
  }

  make[ResourceIdCheck].from { (idCheck: PersistenceIdCheck, moduleTypes: Set[EntityType]) =>
    new ResourceIdCheck(idCheck, moduleTypes)
  }

  include(PermissionsModule)
  include(AclsModule)
  include(RealmsModule)
  include(OrganizationsModule)
  include(ProjectsModule)
  include(ResolversModule)
  include(SchemasModule)
  include(ResourcesModule)
  include(IdentitiesModule)
  include(VersionModule)
  include(QuotasModule)

  private def projection[A: Decoder: Encoder](
      empty: => A,
      system: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): Task[Projection[A]] = {
    implicit val as: ActorSystem[Nothing] = system
    implicit val c: Clock[UIO]            = clock
    appCfg.database.flavour match {
      case Postgres  => Projection.postgres(appCfg.database.postgres, empty)
      case Cassandra => Projection.cassandra(appCfg.database.cassandra, empty)
    }
  }
}

object DeltaModule {

  /**
    * Complete service wiring definitions.
    *
    * @param appCfg      the application configuration
    * @param config      the raw merged and resolved configuration
    * @param classLoader the aggregated class loader
    */
  final def apply(
      appCfg: AppConfig,
      config: Config,
      classLoader: ClassLoader
  ): DeltaModule =
    new DeltaModule(appCfg, config)(classLoader)
}
