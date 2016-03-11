import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;


// $Id: Httpd.java 358 2014-03-05 08:10:19Z coelho $

// 1. importez les packages nécessaires

/** cette classe gère que requête HTTP.
 */
class HandleHttpRequest extends Thread
{
  // ATTRIBUTS
  protected String docroot;
  protected Socket client;

  // CONSTRUCTEUR
  public HandleHttpRequest(String docroot, Socket client)
  {
    this.docroot = docroot;
    this.client = client;
  }

  public String extractPathFromRequestFirstLine(String firstLine){
    return firstLine.split(" ")[1];
  }

  public String getDestinationMimeType(String path) throws Exception{
    int i = path.lastIndexOf('.');
    String extension = ((i == 0) ? path : path.substring(i + 1));

    switch(extension){
      // HTML pages
      case "html":
      case "htm":
      case "xhtml":
        return "text/html";

      // CSS StyleSheets
      case "css":
        return "text/css";

      // JS scripts
      case "js":
        return "application/x-javascript";

      // Common image formats used on the Web
      case "png":
        return "image/png";

      case "jpg":
      case "jpe":
      case "jpeg":
        return "image/jpeg";

      case "gif":
        return "image/gif";

      // PDF files
      case "pdf":
        return "application/pdf";

      case "ttf":
        return "application/x-font-truetype";

      case "otf":
        return "application/x-font-opentype";

      default:
          throw new Exception("Unsupported file extension: " + extension +
                  " !");
    }
  }

  public boolean pathIsAcceptable(String path) throws IOException{
    // 1. Check that our path is a subpath of our DocumentRoot
    File f = new File(path);
    String absolutePath = f.getCanonicalPath();
    File rootFile = new File(this.docroot);
    String absoluteRoot = rootFile.getCanonicalPath();
    if(!absolutePath.startsWith(absoluteRoot))
        return false;

    // 2. We check that the file actually exists before we start replying
    if(!f.exists())
        return false;

    return true;
  }

  public void replyWith404(PrintStream out){
    out.println("HTTP/1.0 404 Not Found");
    out.println("Content-Type: text/plain");
    out.println("Content-Length: 17");
    out.println("Server: Tomahawk");
    out.println("\n");
    out.println("File not found !");
    out.flush();
  }

  public void replyWith500(PrintStream out){
    out.println("HTTP/1.0 500 Internal Server Error");
    out.println("Content-Type: text/plain");
    out.println("Content-Length: 24");
    out.println("Server: Tomahawk");
    out.println("\n");
    out.println("Internal Server Error !");
    out.flush();
  }

  public void replyWithFileContent(String type, String path, PrintStream out)
    throws FileNotFoundException, IOException{
    // We don't need to check much about the file, we already checked for
    // it's validity at this point... hopefully, but let's open now so that
    // if an exception is to be thrown, it will be done before we start
    // replying "200 OK" to the dearest of all our clients !
    File f = new File(path);
    FileInputStream fis = new FileInputStream(f);

    // Ok now we can start sending our headers, if an exception happens while
    // our file is being read though, there's not way to tell what the client
    // will get :/
    out.println("HTTP/1.0 200 OK");
    out.println("Content-Type: " + type);
    out.println("Content-Length: " + f.length());
    // Parce que le comique de répétition, c'est tellement pourri que ça fait
    // rire les gens...
    out.println("Server: Tomahawk 2.0");
    out.println("");

    swallowFileContent(fis, out);

    out.flush();
  }

  public void swallowFileContent(InputStream fis, PrintStream fos)
      throws IOException{
    int n;
    byte[] buffer = new byte[65536];
    while((n = fis.read(buffer)) != -1)
        fos.write(buffer, 0, n);
  }

  // exécution de la thread
  public void run()
  {
    BufferedReader in = null;
    PrintStream out = null;
    try
    {
      in = new BufferedReader(new InputStreamReader(client.getInputStream()));
      out = new PrintStream(client.getOutputStream());

      String line, type;

      // 2. lire simplement la première ligne de la requête
      line = in.readLine();

      // 3. découper la ligne pour récupérer le chemin
      //    utiliser la méthode split de la classe String.
      // GET /le/chemin/vers/le/fichier.html HTTP/1.0
      String path = extractPathFromRequestFirstLine(line);
      String localpath = this.docroot + path;

      // Alors là on vérifie que les path donné est bien un sous Path de
      // notre document root... pour les petits malins qui veulent mettre des
      // ".." dans l'URL qu'ils nous passent.
      if(!pathIsAcceptable(localpath))
          throw new FileNotFoundException("The path (" + path + ") isn't a " +
                  "valid one. Let's kick our client out of the way");

      // 4. déterminer le "type" du document selon le suffixe
      //    utiliser la méthode endsWith de la classe String.
      type = getDestinationMimeType(path);

      System.err.println("type = " + type);

      // 5. envoyer la réponse, le type, une ligne vide
      //    puis le contenu du fichier.
      replyWithFileContent(type, localpath, out);

      // fin de la réponse...
    } catch (FileNotFoundException e) {
      // The user asked for something we don't have, he deserves a good old 404
      if(out != null)
          replyWith404(out);
    } catch (Exception e) { // IOExceptions, most likely
      // should generate some 4xx error?
      // If we can still communicate with the client, let's tell him
      // something dirty hit the fan
      if(out != null)
        replyWith500(out);

      // Otherwise let's just put the error in our logs (see below)
      System.err.println(e);
      e.printStackTrace();

    } finally {
      try{
        // Let's free our resources no matter what happens
        if(out != null)
            out.close();
        if(in != null)
            in.close();
        if(this.client != null)
            this.client.close();
      } catch(IOException re) {
          System.err.println(re);
          re.printStackTrace();
      }
    }
  }
}

public class Httpd
{
  /** usage: java Httpd $HOME/public_html/ */
  static public void main(String[] args) throws Exception
  {
    String docroot = args[0];
    ServerSocket serv;

    // 6. lancer un serveur sur le port 8080
    serv = new ServerSocket(8080);

    System.err.println(serv);

    while (true)
    {
      Socket client;

      // 7. attendre un client...
      client = serv.accept();
      System.err.println(client);

      // 8. lancer une thread HandleHttpRequest pour répondre
      HandleHttpRequest handler = new HandleHttpRequest(docroot, client);
      handler.start();
    }
  }
}
