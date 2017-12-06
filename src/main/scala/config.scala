import java.io.FileReader
import java.io.BufferedReader

class Config( fileName: String ) {
  /***************************************************************************
   * Object to read config file
   ***************************************************************************/

  val(
    pajekFile, mergeAlgo, dampingFactor,
    logDir, logWriteLog, rddText, rddJSon, logSteps
  ): (String,String,Double,String,Boolean,Boolean,Int,Boolean) = {

  /***************************************************************************
   * read and parse JSon file content
   ***************************************************************************/
    // read JSon content
    val jsonContent = {
      val reader = new BufferedReader( new FileReader(fileName) )
      var line = reader.readLine
      var wholefile = line
      var looping = true
      while(looping) {
        line = reader.readLine
        looping = ( line != null )
        if(looping)
          wholefile += line
      }
      reader.close
      wholefile
    }

    // parse JSon content
    val json = scala.util.parsing.json.JSON.parseFull(jsonContent)
    .asInstanceOf[Option[Map[String,String]]]

  /***************************************************************************
   * grab data
   ***************************************************************************/
    val pajekFile = json.map(_("Pajek")).getOrElse("pajek.net")
    val mergeAlgo = json.map(_("Algo")).getOrElse("InfoFlow")
    val dampingFactor = json.map(_("damping")).getOrElse("0.85").toDouble
    val logDir = json.map(_("logDir")).getOrElse(".")
    val logWriteLog = json.map(_("logWriteLog")).getOrElse("false").toBoolean
    val rddText = json.map(_("logRddText")).getOrElse("false").toBoolean
    val rddJSon = json.map(_("logRddJSon")).getOrElse("false").toInt
    val logSteps = json.map(_("logSteps")).getOrElse("false").toBoolean

    (
      pajekFile, mergeAlgo, dampingFactor,
      logDir, logWriteLog, rddText, rddJSon, logSteps
    )
  }
}