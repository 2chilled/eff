package org.atnos
package example

import org.specs2._
import org.atnos.eff._, all._, syntax.all._
import cats.syntax.all._
import cats.data._
import Tag._

class StacksSpec extends Specification { def is = s2"""

 Some domains may use different effect stacks sharing some common effects.
 When we want to use functions from 2 different domains we want to unify all the effects into one stack.

 There are 2 ways to do this:

  1. each domain can define functions only requiring effects to be members of a given yet-undefined stack R

    In that case it will be straightforward to combine functions from both domains.

  2. each domain can define functions for a given stack of effects

    In that case we need to use the `into` method to adapt functions returning `Eff[Stack, A]` into `Eff[SuperStack, A]`
    where `SuperStack` contains all the effects of `Stack`.

  The following examples use a Hadoop domain for functions interacting with Hadoop and a S3 domain for functions interacting
  with S3. Notably, the Hadoop stack contains a Reader effect to access the Hadoop configuration whereas the
  S3 stack contains another Reader effect to access the S3 configuration. If we want to interact with both Hadoop and S3,
  we define a HadoopS3 stack containing all the effects from the 2 stacks.

  Two stacks defined with open effects can be used together           $togetherOpen
  Two stacks using different effects can be used together with `into` $together

"""


  def togetherOpen = {

    import HadoopOpenStack._, Hadoop._
    import S3OpenStack._, S3._

    type HadoopS3 = S3Reader |: HadoopReader |: WriterString |: Eval |: NoEffect

    val action = for {
      s <- readFile[HadoopS3]("/tmp/data")
      _ <- writeFile[HadoopS3]("key", s)
    } yield ()

    action.runReaderTagged(S3Conf("bucket")).runReaderTagged(HadoopConf(10)).runWriter.runEval.run ====
      (((), List("Reading from /tmp/data", "Writing to bucket bucket: 10")))
  }

  def together = {
    import HadoopClosedStack._, Hadoop._
    import S3ClosedStack._, S3._

    type HadoopS3 = S3Reader |: HadoopReader |: WriterString |: Eval |: NoEffect

    val action = for {
      s <- readFile("/tmp/data").into[HadoopS3]
      _ <- writeFile("key", s)  .into[HadoopS3]
    } yield ()

    action.runReaderTagged(S3Conf("bucket")).runReaderTagged(HadoopConf(10)).runWriter.runEval.run ====
      (((), List("Reading from /tmp/data", "Writing to bucket bucket: 10")))
  }

  /**
   * STACKS
   */
  type WriterString[A] = Writer[String, A]

  object S3 {
    trait S3Tag
    case class S3Conf(bucket: String)

    type S3Reader[A] = Reader[S3Conf, A] @@ S3Tag

    type S3 = S3Reader |: WriterString |: Eval |: NoEffect

    implicit val S3ReaderMember: Member.Aux[S3Reader, S3, WriterString |: Eval |: NoEffect] =
      Member.first

    implicit val WriterStringMember: Member.Aux[WriterString, S3, S3Reader |: Eval |: NoEffect] =
      Member.successor

    implicit val EvalMember: Member.Aux[Eval, S3, S3Reader |: WriterString |: NoEffect] =
      Member.successor
  }

  object Hadoop {

    trait HadoopTag
    case class HadoopConf(mappers: Int)

    type HadoopReader[A] = Reader[HadoopConf, A] @@ HadoopTag

    type Hadoop = HadoopReader |: WriterString |: Eval |: NoEffect

    implicit val HadoopReaderMember: Member.Aux[HadoopReader, Hadoop, WriterString |: Eval |: NoEffect] =
      Member.first

    implicit val WriterStringMember: Member.Aux[WriterString, Hadoop, HadoopReader |: Eval |: NoEffect] =
      Member.successor

    implicit val EvalMember: Member.Aux[Eval, Hadoop, HadoopReader |: WriterString |: NoEffect] =
      Member.successor
  }

  object S3OpenStack {

    import S3.{S3Reader, S3Conf, S3Tag}

    def askS3Conf[R](implicit m: S3Reader <= R): Eff[R, S3Conf] =
      ReaderEffect.ask(Member.untagMember[Reader[S3Conf, ?], R, S3Tag](m))

    def writeFile[R](key: String, content: String)(implicit r: S3Reader <= R, w: WriterString <= R): Eff[R, Unit] =
      for {
        c <- askS3Conf(r)
        _ <- tell[R, String]("Writing to bucket "+c.bucket+": "+content)(w)
      } yield ()

  }

  object HadoopOpenStack {
    import Hadoop.{HadoopReader, HadoopConf, HadoopTag}

    def askHadoopConf[R](implicit m: HadoopReader <= R): Eff[R, HadoopConf] =
      ReaderEffect.ask(Member.untagMember[Reader[HadoopConf, ?], R, HadoopTag](m))

    def readFile[R](path: String)(implicit r: HadoopReader <= R, w: WriterString <= R): Eff[R, String] =
      for {
        c <- askHadoopConf(r)
        _ <- tell[R, String]("Reading from "+path)(w)
      } yield c.mappers.toString
  }


  object HadoopClosedStack {
    import Hadoop._

    def askHadoopConf: Eff[Hadoop, HadoopConf] =
      ReaderEffect.askTagged

    def readFile(path: String): Eff[Hadoop, String] =
      for {
        c <- askHadoopConf
        _ <- tell[Hadoop, String]("Reading from "+path)
      } yield c.mappers.toString
  }

  object S3ClosedStack {

    import S3._

    def askS3Conf: Eff[S3, S3Conf] =
      ReaderEffect.askTagged

    def writeFile(key: String, content: String): Eff[S3, Unit] =
      for {
        c <- askS3Conf
        _ <- tell[S3, String]("Writing to bucket "+c.bucket+": "+content)
      } yield ()

  }

}
