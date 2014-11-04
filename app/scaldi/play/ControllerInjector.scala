package scaldi.play

import play.api.{Application, Play}
import play.api.mvc.Controller
import scaldi._
import scala.reflect.runtime.universe.{Type, runtimeMirror, typeTag}
import java.lang.reflect.InvocationTargetException

/**
 * <p>Injector for the Play applications that creates controller bindings on the fly.
 * The preferred way to use it is by adding it to the module composition
 * at the very end, so that it would be possible to override default instantiation
 * strategy in user-defined modules.
 *
 * <p>Here is an example:
 *
 * <pre class="stHighlight">
 * object Global extends GlobalSettings with ScaldiSupport {
 *   def applicationModule = new UserModule :: new DbModule :: new ControllerInjector
 * }
 * </pre>
 */
class ControllerInjector extends MutableInjectorUser with InjectorWithLifecycle[ControllerInjector] with ShutdownHookLifecycleManager {

  private var bindings: List[BindingWithLifecycle] = Nil

  def getBindingInternal(identifiers: List[Identifier]) = identifiers match {
    case TypeTagIdentifier(tpe) :: Nil if tpe <:< typeTag[Controller].tpe =>
      bindings.find(_ isDefinedFor identifiers) orElse {
        this.synchronized {
          bindings.find(_ isDefinedFor identifiers) orElse {
            val binding = createBinding(tpe, identifiers)
            
            bindings = bindings :+ binding
            
            Some(binding)
          }
        }
      }
    case _ => None
  }

  private def createBinding(tpe: Type, identifiers: List[Identifier]) = {
    val controller =
      tpe.decls
        .filter(_.isMethod)
        .map(_.asMethod)
        .find(m => m.isConstructor && (m.paramLists match {
          case List(Nil) => true
          case List(Nil, List(paramType)) if paramType.isImplicit && paramType.typeSignature <:< typeTag[Injector].tpe  => true
          case _ => false
        }))
        .map { constructor =>
          import Injectable._

          val app = inject [Application]
          val mirror = runtimeMirror(app.classloader)
          val constructorMirror = mirror.reflectClass(tpe.typeSymbol.asClass).reflectConstructor(constructor)

          try {
            constructor.paramLists match {
              case List(Nil, List(paramType)) => constructorMirror(injector)
              case List(Nil) => constructorMirror()
            }
          } catch {
            case e: InvocationTargetException => throw e.getCause
          }
        }
        .getOrElse (throw new IllegalArgumentException(
          s"Type $tpe does not have no-argument constructor or constructor with single implicit `Injector` argument."))
    
    LazyBinding(Some(() => controller), identifiers)
  } 
  
  def getBindingsInternal(identifiers: List[Identifier]) = getBindingInternal(identifiers).toList

  protected def init(lifecycleManager: LifecycleManager) = () => ()
}
