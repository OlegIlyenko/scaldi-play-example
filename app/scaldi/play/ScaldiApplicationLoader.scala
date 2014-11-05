package scaldi.play

import play.api._
import play.api.ApplicationLoader.Context
import play.api.inject.{Module => PlayModule, Binding => PlayBinding, Injector => PlayInjector, _}
import play.core.WebCommands
import scaldi._
import scaldi.util.ReflectionHelper
import javax.inject.{Provider, Singleton, Qualifier, Named}
import scaldi.jsr330.{OnDemandAnnotationInjector, AnnotationBinding, AnnotationIdentifier}
import scala.reflect.runtime.universe.typeOf
import Injectable.{noBindingFound, injectWithDefault}
import com.google.inject.CreationException

class ScaldiApplicationLoader extends ApplicationLoader {
  import ScaldiApplicationLoader._

  def load(context: Context)  = {
    val env = context.environment
    val global = GlobalSettings(context.initialConfiguration, env)
    val configuration = global.onLoadConfig(context.initialConfiguration, env.rootPath, env.classLoader, env.mode)

    Logger.configure(env, configuration)

    val cacheControllers = configuration.getBoolean("scaldi.controller.cache") getOrElse true
    val globalInjector = global match {
      case s: ScaldiSupport => s.applicationModule
      case _ => NilInjector
    }

    val commonBindings = new Module {
      bind [GlobalSettings] to global
      bind [OptionalSourceMapper] to new OptionalSourceMapper(context.sourceMapper)
      bind [WebCommands] to context.webCommands
      bind [PlayInjector] to {
        if (cacheControllers) new ControllerCachingPlayInjector(new ScaldiInjector)
        else new ScaldiInjector
      }

      binding identifiedBy 'playMode to inject[Application].mode
      binding identifiedBy 'config to inject[Application].configuration
    }

    val configModules = Modules.locate(env, configuration).map(convertToScaldiModule(env, configuration, _))

    try {
      implicit val injector = createScaldiInjector(configModules ++ Seq(commonBindings, globalInjector), configuration)

      Injectable.inject [Application]
    } catch {
      case e: CreationException => e.getCause match {
        case p: PlayException => throw p
        case _ => throw e
      }
    }
  }

  override def createInjector(environment: Environment, configuration: Configuration, modules: Seq[Any]) = {
    val commonBindings = new Module {
      bind [PlayInjector] to new ScaldiInjector
    }
    val configModules = modules.map(convertToScaldiModule(environment, configuration, _))
    implicit val injector = createScaldiInjector(configModules :+ commonBindings, configuration)

    Some(Injectable.inject [PlayInjector])
  }
}

object ScaldiApplicationLoader {
  def createScaldiInjector(injectors: Seq[Injector], config: Configuration) = {
    val standard = Seq(TypesafeConfigInjector(config.underlying), new OnDemandAnnotationInjector)

    (injectors ++ standard).reduce(_ :: _)
  }

  def convertToScaldiModule(env: Environment, conf: Configuration, module: Any): Injector = module match {
    case playModule: PlayModule => toScaldiBindings(playModule.bindings(env, conf))
    case inj: Injector => inj
    case unknown =>
      throw new PlayException("Unknown module type", s"Module [$unknown] is not a Play module or a Scaldi module")
  }

  def identifiersForKey[T](key: BindingKey[T]) = {
    val mirror = ReflectionHelper.mirror
    val keyType = mirror.classSymbol(key.clazz).toType

    val qualifier = key.qualifier map {
      case QualifierInstance(a: Named) => StringIdentifier(a.value())
      case QualifierInstance(a) if a.getClass.getAnnotation(classOf[Qualifier]) != null =>
        AnnotationIdentifier(mirror.classSymbol(a.getClass).toType)
      case QualifierClass(clazz) => AnnotationIdentifier(mirror.classSymbol(clazz).toType)
    }

    (keyType, TypeTagIdentifier(keyType) :: qualifier.toList)
  }

  def toScaldiBindings(bindings: Seq[PlayBinding[_]]): Injector = {
    val mirror = ReflectionHelper.mirror

    val scaldiBindings = (inj: Injector) => bindings.toList.map { binding =>
      val scope = binding.scope map (mirror.classSymbol(_).toType)
      val singleton = scope.exists(_ =:= typeOf[Singleton])
      val (keyType, identifiers) = identifiersForKey(binding.key)

      binding.target match {
        case Some(ProviderTarget(provider)) if singleton && binding.eager =>
          NonLazyBinding(Some(() => provider.get), identifiers)
        case Some(ProviderTarget(provider)) if singleton =>
          LazyBinding(Some(() => provider.get), identifiers)
        case Some(ProviderTarget(provider)) =>
          ProviderBinding(() => provider.get, identifiers)

        case Some(BindingKeyTarget(key)) if singleton && binding.eager =>
          NonLazyBinding(Some(() => injectWithDefault(inj, noBindingFound(identifiers))(identifiers)), identifiers)
        case Some(BindingKeyTarget(key)) if singleton =>
          LazyBinding(Some(() => injectWithDefault(inj, noBindingFound(identifiers))(identifiers)), identifiers)
        case Some(BindingKeyTarget(key)) =>
          ProviderBinding(() => injectWithDefault(inj, noBindingFound(identifiers))(identifiers), identifiers)

        case Some(ProviderConstructionTarget(provider)) =>
          AnnotationBinding(
            tpe = mirror.classSymbol(provider).toType,
            injector = () => inj,
            identifiers = identifiers,
            eager = binding.eager,
            forcedScope = scope,
            bindingConverter = Some(_.asInstanceOf[Provider[AnyRef]].get()))
        case Some(ConstructionTarget(implementation)) =>
          AnnotationBinding(
            tpe = mirror.classSymbol(implementation).toType,
            injector = () => inj,
            identifiers = identifiers,
            eager = binding.eager,
            forcedScope = scope)

        case None =>
          AnnotationBinding(
            tpe = keyType,
            injector = () => inj,
            identifiers = identifiers,
            eager = binding.eager,
            forcedScope = scope)
      }
    }

    new SimpleContainerInjector(scaldiBindings)
  }
}
