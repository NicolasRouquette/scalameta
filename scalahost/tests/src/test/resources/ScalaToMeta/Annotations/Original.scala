import scala.annotation._
import java.lang.annotation._
import java.lang.annotation.RetentionPolicy._

object Annotations {
  @SerialVersionUID(value = 1L) class C1
  @SerialVersionUID(value = 0 + 1L) class C2
  @SerialVersionUID(value = -1L) class C3
  @Retention(RetentionPolicy.RUNTIME) class annot1
  @Retention(RUNTIME) class annot2
}