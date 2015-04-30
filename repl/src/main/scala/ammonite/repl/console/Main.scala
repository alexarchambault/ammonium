package ammonite.repl
package console

import java.io.{PrintStream, OutputStream, InputStream}
import ammonite.compiler._
import ammonite.interpreter._
import ammonite.pprint
import acyclic.file

import scala.annotation.tailrec
import scala.util.Try

class Main(input: InputStream,
           output: OutputStream,
           colorSet: ColorSet = ColorSet.Default,
           pprintConfig: pprint.Config = pprint.Config.Colors.PPrintConfig,
           shellPrompt0: String = "@",
           initialHistory: Seq[String] = Nil,
           saveHistory: String => Unit = _ => (),
           predef: String = Main.defaultPredef) {

  val shellPrompt = Ref(shellPrompt0)

  val frontEnd = JLineFrontend(
    input,
    output,
    colorSet.prompt + shellPrompt() + Console.RESET,
    interp.pressy.complete(_, interp.eval.previousImportBlock, _),
    initialHistory
  )

  val interp: ReplInterpreter.Repl = ReplInterpreter(
    frontEnd.update,
    shellPrompt,
    pprintConfig.copy(maxWidth = frontEnd.width),
    colorSet,
    stdout = new PrintStream(output).println,
    initialHistory = initialHistory
  )

  def action() = for{
    // Condition to short circuit early if `interp` hasn't finished evaluating
    line <- frontEnd.action(interp.buffered)
    _ <- Signaller("INT") { interp.mainThread.stop() }
    out <- interp.processLine(line, (f, x) => {saveHistory(x); f(x)}, _.foreach(print))
  } yield {
    println()
    out
  }


  def run() = {
    @tailrec def loop(): Unit = {
      val res = action()
      if (interp.handleOutput(res)) loop()
    }
    loop()
  }
}

object Main{
  val defaultPredef = """"""
  def main(args: Array[String]) = {
    println("Loading Ammonite Repl...")
    import ammonite.ops._
    val saveFile = home/".amm"
    val delimiter = "\n\n\n"
    val shell = new Main(
      System.in, System.out,
      initialHistory = Try{read! saveFile}.getOrElse("").split(delimiter),
      saveHistory = s => write.append(home/".amm", s + delimiter)
    )
    shell.run()

  }
}
