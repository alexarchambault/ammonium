package ammonite.shell

import acyclic.file
import ammonite.interpreter.{BacktickWrap, Res}
import org.parboiled2.ParseError

import scala.reflect.internal.Flags
import scala.tools.nsc.{Global => G}

/**
 * Converts REPL-style snippets into full-fledged Scala source files,
 * ready to feed into the compiler. Each source-string is turned into
 * three things:
 */
trait Preprocessor[T] {
  def apply(code: String, wrapperId: String): Res[T]
}
object Preprocessor{

  case class Output(code: String, printer: Seq[String])

  trait Display {

    def definition(definitionLabel: String, name: String): String

    def identity(ident: String): String

    def lazyIdentity(ident: String): String

    def displayImport(imported: String): String

  }
  
  trait ShellDisplay extends Display {

    def definition(definitionLabel: String, name: String) =
      s"""Iterator(ReplBridge.shell.shellPrintDef("$definitionLabel", "$name"))"""

    def pprintSignature(ident: String) = s"""Iterator(ReplBridge.shell.shellPPrint($$user.$ident, "$ident"))"""

    def identity(ident: String) = {
      pprintSignature(ident) +
        s""" ++ Iterator(" = ") ++ ammonite.pprint.PPrint($$user.$ident)"""
    }

    def lazyIdentity(ident: String) =
      s"""${pprintSignature(ident)} ++ Iterator(" = <lazy>")"""

    def displayImport(imported: String) =
      s"""Iterator(ReplBridge.shell.shellPrintImport("$imported"))"""

  }

  class PreprocessorParser(parse: => String => Either[String, Seq[G#Tree]], display: Display) extends Preprocessor[Output] {

    def Processor(cond: PartialFunction[(String, String, G#Tree), Preprocessor.Output]) = {
      (code: String, name: String, tree: G#Tree) => cond.lift(name, code, tree)
    }

    /**
     * Processors for declarations which all have the same shape
     */
    def DefProc(definitionLabel: String)(cond: PartialFunction[G#Tree, G#Name]) =
      (code: String, name: String, tree: G#Tree) =>
        cond.lift(tree).map{ name =>
          Preprocessor.Output(
            code,
            Seq(display.definition(definitionLabel, BacktickWrap(name.decoded)))
          )
        }

    val ObjectDef = DefProc("object"){case m: G#ModuleDef => m.name}
    val ClassDef = DefProc("class"){ case m: G#ClassDef if !m.mods.isTrait => m.name }
    val TraitDef =  DefProc("trait"){ case m: G#ClassDef if m.mods.isTrait => m.name }
    val DefDef = DefProc("function"){ case m: G#DefDef => m.name }
    val TypeDef = DefProc("type"){ case m: G#TypeDef => m.name }

    val PatVarDef = Processor { case (name, code, t: G#ValDef) =>
      Output(
        code,
        // Try to leave out all synthetics; we don't actually have proper
        // synthetic flags right now, because we're dumb-parsing it and not putting
        // it through a full compilation
        if (t.name.decoded.contains("$")) Nil
        else if (!t.mods.hasFlag(Flags.LAZY)) Seq(display.identity(BacktickWrap.apply(t.name.decoded)))
        else Seq(display.lazyIdentity(BacktickWrap.apply(t.name.decoded)))
      )
    }

    val Import = Processor{
      case (name, code, tree: G#Import) =>
        val Array(keyword, body) = code.split(" ", 2)
        Output(code, Seq(display.displayImport(body)))
    }

    val Expr = Processor{
      case (name, code, tree) => Output(s"val $name = (\n$code\n)", Seq(display.identity(name)))
    }

    val decls = Seq[(String, String, G#Tree) => Option[Preprocessor.Output]](
      ObjectDef, ClassDef, TraitDef, DefDef, TypeDef, PatVarDef, Import, Expr
    )

    def apply(code: String, wrapperId: String): Res[Preprocessor.Output] = {
      val splitter = new scalaParser.Scala(code){
        def Split = {
          def Prelude = rule( Annot.* ~ `implicit`.? ~ `lazy`.? ~ LocalMod.* )
          rule( Semis.? ~ capture(Import | Prelude ~ BlockDef | StatCtx.Expr).*(Semis) ~ Semis.? ~ WL ~ EOI)
        }
      }
      splitter.Split.run() match {
        case scala.util.Failure(e @ ParseError(p, pp, t)) if p.index == code.length => Res.Buffer(code)
        case scala.util.Failure(e) => Res.Failure(parse(code).left.get)
        case scala.util.Success(Nil) => Res.Skip
        case scala.util.Success(postSplit: Seq[String]) => complete(code, wrapperId, postSplit.map(_.trim))
      }
    }
    
    def complete(code: String, wrapperId: String, postSplit: Seq[String]) = {
      val reParsed = postSplit.map(p => (parse(p), p))
      val errors = reParsed.collect{case (Left(e), _) => e }
      if (errors.length != 0) Res.Failure(errors.mkString("\n"))
      else {
        val allDecls = for (((Right(trees), code), i) <- reParsed.zipWithIndex) yield {
          // Suffix the name of the result variable with the index of
          // the tree if there is more than one statement in this command
          val suffix = if (reParsed.length > 1) "_" + i else ""
          def handleTree(t: G#Tree) = {
            decls.iterator.flatMap(_.apply(code, "res" + wrapperId + suffix, t)).next()
          }
          trees match {
            case Seq(tree) => handleTree(tree)

            // This handles the multi-import case `import a.b, c.d`
            case trees if trees.forall(_.isInstanceOf[G#Import]) => handleTree(trees(0))

            // AFAIK this can only happen for pattern-matching multi-assignment,
            // which for some reason parse into a list of statements. In such a
            // scenario, aggregate all their printers, but only output the code once
            case trees =>
              val printers = for {
                tree <- trees
                if tree.isInstanceOf[G#ValDef]
                Preprocessor.Output(_, printers) = handleTree(tree)
                printer <- printers
              } yield printer
              Preprocessor.Output(code, printers)
          }
        }
        Res(
          allDecls.reduceOption { (a, b) =>
            Output(
              a.code + ";" + b.code,
              a.printer ++ b.printer
            )
          },
          "Don't know how to handle " + code
        )
      }

    }
  }

  def apply(parse: => String => Either[String, Seq[G#Tree]]): Preprocessor[Output] =
    new PreprocessorParser(parse, new ShellDisplay {})
}

