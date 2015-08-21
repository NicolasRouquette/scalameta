package scala.meta

import org.scalameta.adt._
import org.scalameta.unreachable

@root trait DottyhostException extends Exception
@leaf class ConvertException(culprit: Any, message: String, cause: Option[Throwable] = None) extends Exception(message, cause.orNull) with DottyhostException
@leaf class StandaloneException(message: String) extends Exception(message) with DottyhostException
