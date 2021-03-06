package api.dfs

import java.io.IOException
import java.net.URI
import java.security.PrivilegedExceptionAction
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._
import org.apache.hadoop.fs.permission.FsPermission
import org.apache.hadoop.security.UserGroupInformation
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee.{ Enumerator, Iteratee }
import play.api.libs.json._
import play.api.mvc.Results._
import play.api.mvc.{ BodyParsers, SimpleResult }

object Hdfs {

  class HdfsApi(val defaultFS: String, val username: String) {

    val conf = new Configuration()
    val dfs = FileSystem.get(URI.create(defaultFS), conf)
    val ugi: UserGroupInformation = UserGroupInformation.createProxyUser(username, UserGroupInformation.getLoginUser());

    def listdir(path: Path): Array[FileStatus] = {
      ugi.doAs(new PrivilegedExceptionAction[Array[FileStatus]]() {
        def run = { dfs.listStatus(path) }
      })
    }

    /**
     * @return "/user/ + username" directory if it exists otherwice "/"
     */
    def homeDir(user: String = username): String = {
      if (dfs.isDirectory("/user/" + user)) "/user/" + user else "/"
    }

    def setPermission(path: Path, mode: Short): Unit = {
      ugi.doAs(new PrivilegedExceptionAction[Unit]() {
        def run = { dfs.setPermission(path, new FsPermission(mode)) }
      })
    }

    def setOwner(path: Path, username: Option[String], group: Option[String]) = {
      ugi.doAs(new PrivilegedExceptionAction[Unit]() {
        def run = { dfs.setOwner(path, username.getOrElse(null), group.getOrElse(null)) }
      })
    }

    def open(path: Path): FSDataInputStream = {
      ugi.doAs(new PrivilegedExceptionAction[FSDataInputStream]() {
        def run = { dfs.open(path) }
      })
    }

    /**
     * Return a file status object that represents the path.
     * @param f - The path we want information from
     * @return a FileStatus object
     * @throws FileNotFoundException - when the path does not exist; IOException see specific implementation
     * @throws IOException
     */
    def fileStatus(path: Path): FileStatus = {
      ugi.doAs(new PrivilegedExceptionAction[FileStatus]() {
        def run = { dfs.getFileStatus(path) }
      })
    }

    def mkdir(path: Path): Boolean = {
      ugi.doAs(new PrivilegedExceptionAction[Boolean]() {
        def run = { dfs.mkdirs(path) }
      })
    }

    def create(path: Path, overwrite: Boolean = false): FSDataOutputStream = {
      ugi.doAs(new PrivilegedExceptionAction[FSDataOutputStream]() {
        def run = { dfs.create(path, overwrite) }
      })
    }

    def rename(src: Path, dst: Path): Boolean = {
      ugi.doAs(new PrivilegedExceptionAction[Boolean]() {
        def run = { dfs.rename(src, dst) }
      })
    }

    def write(path: Path, content: Enumerator[Array[Byte]], offset: Int = 0): Unit = {
      ugi.doAs(new PrivilegedExceptionAction[Unit]() {
        def run = {
          val outputStream = dfs.create(path, false)
          val writeIteratee = Iteratee.fold[Array[Byte], Int](offset) { (length, bytes) =>
            outputStream.write(bytes, length, bytes.length)
            length + bytes.length
          }
          val result = content.run(writeIteratee)
          result.onSuccess {
            case x =>
              outputStream.close()
              play.api.Logger.debug(s"Wrote $x bytes to $path")
          }
        }
      })
    }

    /**
     * HDFS Upload Body Parser. Doesn't buffer whole file before upload to HDFS, but rather directly upload each comming chunk to HDFS.
     */
    def hdfsUploadHandler(path: Path) = BodyParsers.parse.Multipart.handleFilePart {
      case BodyParsers.parse.Multipart.FileInfo(partName, filename, contentType) =>
        val outputStream = dfs.create(new Path(path, filename), false)
        ugi.doAs(new PrivilegedExceptionAction[Iteratee[Array[Byte], Either[SimpleResult, Path]]]() {
          def run = {
            Iteratee.foreach[Array[Byte]] {
              bytes => outputStream.write(bytes)
            } map { _ =>
              try { outputStream.close() }
              catch {
                case ex: IOException => Left(BadRequest(ex.getMessage))
              }
              Right(path)
            }
          }
        })
    }

    def delete(path: Path, recursive: Boolean = false): Boolean = {
      ugi.doAs(new PrivilegedExceptionAction[Boolean]() {
        def run = {
          dfs.delete(path, recursive)
        }
      })
    }

  }

  object HdfsApi {
    def apply(username: String) = {
      new HdfsApi("webhdfs://127.0.0.1:50070", username)
      //new DfsApi("file:///tmp", username)
    }
  }

  import scala.language.implicitConversions
  implicit def string2Path(path: String): Path = new Path(path)
  implicit def string2ArrayBytes(st: String): Array[Byte] = st.getBytes

  implicit class FileStatusJson(fs: FileStatus) {
    def toJson: JsObject = JsObject(Seq(
      "path" -> JsString(Path.getPathWithoutSchemeAndAuthority(fs.getPath).toString),
      "isDirectory" -> JsBoolean(fs.isDirectory),
      "isSymlink" -> JsBoolean(fs.isSymlink),
      "isFile" -> JsBoolean(fs.isFile),
      "modification_time" -> JsNumber(fs.getModificationTime),
      "access_time" -> JsNumber(fs.getAccessTime),
      "owner" -> JsString(fs.getOwner),
      "group" -> JsString(fs.getGroup),
      "permission" -> JsString(fs.getPermission.toString),
      "replication" -> JsNumber(fs.getReplication),
      "length" -> JsNumber(fs.getLen())
    ))
  }

  implicit class FSStatusJson(fs: FsStatus) {
    def toJson = JsObject(Seq(
      "capacity" -> JsNumber(fs.getCapacity()),
      "remaining" -> JsNumber(fs.getRemaining()),
      "used" -> JsNumber(fs.getUsed())
    ))
  }

  implicit class FsArrayJson(files: Array[FileStatus]) {
    def toJson = JsArray(files map (_.toJson))
  }

}
