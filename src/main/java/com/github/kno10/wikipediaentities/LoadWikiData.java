package com.github.kno10.wikipediaentities;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Iterator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.kno10.wikipediaentities.util.Util;

/**
 * Load a WikiData dump, to match Wikipedia articles across languages.
 *
 * @author Erich Schubert
 */
public class LoadWikiData {
  private PrintStream writer;

  public LoadWikiData() throws IOException
  {
    //写入到文件
    String filename = Config.get("wikidata.output");
    writer = Util.openOutput(filename);
  }

  public void load(String fname, String... wikis) throws IOException {

    JsonFactory jackf = new JsonFactory();
    jackf.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
    try (InputStream in = Util.openInput(fname);
        JsonParser parser = jackf.createParser(in)) {
      parser.setCodec(new ObjectMapper());
      parser.nextToken();
      assert (parser.getCurrentToken() == JsonToken.START_ARRAY);
      parser.nextToken();

      StringBuilder buf = new StringBuilder();
      buf.append("WikiDataID");
      for(int i = 0; i < wikis.length; i++) {
        buf.append('\t').append(wikis[i]);
      }
      //output
      buf.append('\n');
      String text = buf.toString();
      System.out.print(text);
      writer.append(text);
      //limit maximum
      //int total_lines = 0;

      lines: while(parser.getCurrentToken() != JsonToken.END_ARRAY) {
        assert (parser.getCurrentToken() == JsonToken.START_OBJECT);
        JsonNode tree = parser.readValueAsTree();
        JsonNode idn = tree.path("id");
        if(!idn.isTextual()) {
          System.err.println("Skipping entry without ID. " + parser.getCurrentLocation().toString());
          continue;
        }
        // Check for instance-of for list and category pages:
        JsonNode claims = tree.path("claims");
        JsonNode iof = claims.path("P31");
        if(iof.isArray()) {
          for(Iterator<JsonNode> it = iof.elements(); it.hasNext();) {
            final JsonNode child = it.next();
            JsonNode ref = child.path("mainsnak").path("datavalue").path("value").path("numeric-id");
            if(ref.isInt()) {
              if(ref.asInt() == 13406463) { // "Wikimedia list article"
                continue lines;
              }
              if(ref.asInt() == 4167836) { // "Wikimedia category article"
                continue lines;
              }
              if(ref.asInt() == 4167410) { // "Wikimedia disambiguation page"
                continue lines;
              }
              // Not reliable: if(ref.asInt() == 14204246) { // "Wikimedia
              // project page"
            }
          }
        }
        //buf.setLength(0);
        buf.delete(0, buf.length());
        buf.append(idn.asText());
        JsonNode sl = tree.path("sitelinks");
        boolean good = false;
        for(int i = 0; i < wikis.length; i++) {
          JsonNode wln = sl.path(wikis[i]).path("title");
          buf.append('\t');
          if(wln.isTextual()) {
            buf.append(wln.asText());
            good |= true;
          }
        }
        if(good) {
          buf.append('\n');
          text = buf.toString();
          System.out.print(text);
          writer.append(text);
          /*
          total_lines++;
          if (total_lines > 10000)
          {
            break;
          }
           */
        }
        parser.nextToken();
      }//end while
      writer.flush();
      writer.close();
    }//end try
  }

  public static void main(String[] args) {
    try {
      new LoadWikiData().load("wikidata-latest-all.json.bz2", "zhwiki");
    }
    catch(IOException e) {
      e.printStackTrace();
    }
  }
}
