package com.github.kno10.wikipediaentities;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.standard.ClassicFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.wikipedia.WikipediaTokenizer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

import com.github.kno10.wikipediaentities.util.FastStringReader;
import com.github.kno10.wikipediaentities.util.Util;

/**
 * Class to load Wikipedia articles into a Lucene index.
 *
 * @author Erich Schubert
 */
public class LuceneWikipediaIndexer {
  /** Lucene field name for text */
  public static final String LUCENE_FIELD_TEXT = "t";

  /** Lucene field name for title */
  public static final String LUCENE_FIELD_TITLE = "c";

  /** Lucene field name for the links */
  public static final String LUCENE_FIELD_LINKS = "l";

  /** Lucene index writer */
  private IndexWriter index;
  private FSDirectory ldir;

  /**
   * Constructor
   *
   * @param dir Directory for Lucene index.
   * @throws IOException on errors opening the lucene index
   */
  public LuceneWikipediaIndexer(String dir) throws IOException {
    ldir = FSDirectory.open(FileSystems.getDefault().getPath(dir));
    IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
    index = new IndexWriter(ldir, config);
  }

  /**
   * Make handler for a single thread.
   *
   * @param handler Subhandlers.
   * @return Threadsafe handler.
   */
  public Handler makeThreadHandler(Handler handler) {
    return new IndexHandler(handler);
  }

  /**
   * Instance for indexing in a single thread.
   *
   * @author Erich Schubert
   */
  private class IndexHandler extends AbstractHandler {
    /** Lucene Wikipedia tokenizer */
    private WikipediaTokenizer tokenizer;

    /** Filtered token stream */
    private TokenStream stream;

    /** Patterns to strip from the wiki text */
    private Matcher stripBasics = Pattern.compile("(<!--.*?-->|<math>(.*?)</math>|</?su[bp]>|^\\s*__\\w+__\\s*$)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE).matcher("");

    /** Pattern to strip all templates, as we cannot reasonably parse them */
    private Matcher stripTemplates = Pattern.compile("\\{\\{([^}{]*?)\\}\\}").matcher("");

    /** Match links, which are not nested. */
    // too much backtracking: private Matcher linkMatcher =
    // Pattern.compile("\\[\\[\\s*([^\\]\\[\\|]*?)(?:\\s*#.*?)?(?:(?:\\s*\\|\\s*[^\\]\\[\\#\\|]*)*\\s*\\|([^\\]\\[\\#\\|]+))?\\s*\\]\\]").matcher("");
    private Matcher linkMatcher = Pattern.compile("\\[\\[\\s*([^\\]\\[\\|]*?)(?:\\s*#.*?)?(?:\\s*\\|(?:[^\\]\\[\\#\\|]*\\|)?\\s*([^\\]\\[\\#\\|]+))?\\s*\\]\\]").matcher("");

    /** More cruft to remove */
    private Matcher stripCruft = Pattern.compile("(?:<ref(?:[^<]*</ref|\\s+name\\s*=\\s*[^<]*|[^<]*/>)>|\\{\\|(.*?)\\|\\}|^ *\\*+|\\[\\[(?:([^\\]\\[]*)\\s*\\|\\s*)?([^\\]\\[]*)\\]\\])", Pattern.CASE_INSENSITIVE).matcher("");

    /** Handler to send link detected events to. */
    Handler handler;

    /**
     * Constructor
     *
     * @param handler Handlers for detected links.
     * @throws IOException on errors opening the Lucene index
     */
    public IndexHandler(Handler handler) {
      Set<String> skip = new HashSet<>();
      skip.add(WikipediaTokenizer.EXTERNAL_LINK_URL);
      stream = tokenizer = new WikipediaTokenizer(WikipediaTokenizer.TOKENS_ONLY, skip);
      stream = new ClassicFilter(stream); // Removes 's etc
      stream = new LowerCaseFilter(stream);
      stream.addAttribute(CharTermAttribute.class);
      this.handler = handler;
    }

    StringBuilder buf = new StringBuilder();

    FastStringReader reader = new FastStringReader("");

    @Override
    public void rawArticle(String prefix, String title, String intext) {
      CharSequence text = intext;
      // System.err.print(title + ": ");
      stripBasics.reset(text);
      text = stripBasics.replaceAll("");
      for(int i = 0; i < 4; i++) {
        stripTemplates.reset(text);
        String text2 = stripTemplates.replaceAll("");
        if(text2.equals(text))
          break; // No more changes
        text = text2;
      }
      { // Parse, and replace links with their text only:
        buf.setLength(0); // clear
        int pos = 0;
        linkMatcher.reset(text);
        while(linkMatcher.find()) {
          buf.append(text, pos, linkMatcher.start());
          String targ = linkMatcher.group(1);
          if(targ == null || targ.length() == 0) {
            buf.append(linkMatcher.group(2));
            pos = linkMatcher.end();
            continue; // Internal link.
          }
          targ = Util.normalizeLink(targ);
          if(targ == null || targ.length() == 0) {
            System.err.println(linkMatcher.group(0));
            continue;
          }
          final String[] spl = targ.split(":");
          String targl = spl.length > 0 ? spl[0].trim() : targ;
          if(targ.charAt(0) == ':' || "file".equalsIgnoreCase(targl) || "wikisource".equalsIgnoreCase(targl) //
          || "category".equalsIgnoreCase(targl) || "kategorie".equalsIgnoreCase(targl) //
          || "catégorie".equalsIgnoreCase(targl) || "categoría".equalsIgnoreCase(targl) //
          || "wikipedia".equalsIgnoreCase(targl) || "commons".equalsIgnoreCase(targl) || "image".equalsIgnoreCase(targl)//
          || "fichier".equalsIgnoreCase(targl) || "datei".equalsIgnoreCase(targl) || "bild".equalsIgnoreCase(targl) //
          || "archivo".equalsIgnoreCase(targl) || "imagen".equalsIgnoreCase(targl))
            continue;
          String labl = linkMatcher.group(2);
          if(labl == null)
            labl = targ;
          labl = labl.replace('\n', ' ').trim();
          targ = prefix + targ;
          if(addLink(targ, labl))
            handler.linkDetected(prefix, title, labl, targ);

          buf.append(labl);
          pos = linkMatcher.end();
        }
        buf.append(text, pos, text.length());
        text = buf;
      }
      stripCruft.reset(text);
      text = stripCruft.replaceAll(""); // Converts to string!

      try {
        Document doc = new Document();
        doc.add(new StoredField(LUCENE_FIELD_TITLE, prefix + title));
        doc.add(new StoredField(LUCENE_FIELD_LINKS, serializeLinks()));

        tokenizer.reset();
        stream.reset();
        tokenizer.setReader(reader.reset(text));
        doc.add(new TextField(LUCENE_FIELD_TEXT, stream));
        index.addDocument(doc);
      }
      catch(IOException e) {
        e.printStackTrace();
        System.exit(1);
      }
      clearLinks();

      handler.rawArticle(prefix, title, intext);
    }

    ArrayList<String> links = new ArrayList<>();

    boolean addLink(String target, String label) {
      // There won't be that many duplicates for a hash map to pay off
      for(int i = 0, l = links.size(); i < l; i += 2) {
        if(links.get(i).equals(target) && links.get(i + 1).equals(label)) {
          return false; // Already in the document
        }
      }
      links.add(target);
      links.add(label);
      return true;
    }

    String serializeLinks() {
      buf.setLength(0);
      for(int i = 0; i < links.size(); i++) {
        if(i > 0) {
          buf.append('\t');
        }
        buf.append(links.get(i));
      }
      return buf.toString();
    }

    void clearLinks() {
      links.clear();
    }

    @Override
    public void close() {
      handler.close();
    }
  }

  public void close() throws IOException {
    System.err.format("Closing %s output.\n", getClass().getSimpleName());
    index.commit();
    index.close();
  }
}
