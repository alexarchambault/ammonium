package ammonite.interpreter

object EvaluatorTests extends tests.EvaluatorTests(new SparkChecker, wrapperInstance = Some(SparkIvyPPrintInterpreter.instanceSymbol))
