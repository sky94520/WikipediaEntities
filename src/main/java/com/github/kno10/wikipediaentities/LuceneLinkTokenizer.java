package com.github.kno10.wikipediaentities;

import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.procedure.TObjectIntProcedure;

import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.ClassicFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.wikipedia.WikipediaTokenizer;
import org.apache.lucene.util.Version;

/**
 * Tokenize link texts seen in Wikipedia, to build a list of common link titles.
 * Count how often each target occurs.
 * 
 * @author Erich Schubert
 */
public class LuceneLinkTokenizer extends AbstractHandler {
	/** Lucene Wikipedia tokenizer */
	WikipediaTokenizer tokenizer;

	/** Filtered token stream */
	TokenStream stream;

	/** Lucene character term attribute */
	CharTermAttribute termAtt;

	/** Output file name */
	private String out;

	/** Link text -> map(target -> count) */
	Map<String, CounterSet<String>> links = new HashMap<>();

	/** Minimum support to report */
	static final int MINSUPP = 2;

	/** Filter to only retain links with minimum support */
	private CounterSet.CounterFilter filter = new CounterSet.CounterFilter(
			MINSUPP);

	/** Redirect collector */
	private RedirectCollector r;

	/**
	 * Constructor
	 * 
	 * @param out
	 *            Output file name
	 * @param r
	 *            Redirect collector
	 */
	public LuceneLinkTokenizer(String out, RedirectCollector r) {
		tokenizer = new WikipediaTokenizer(null);
		stream = tokenizer;
		// stream = new PorterStemFilter(tokenizer);
		stream = new ClassicFilter(stream);
		stream = new LowerCaseFilter(Version.LUCENE_36, stream);
		termAtt = stream.addAttribute(CharTermAttribute.class);
		this.out = out;
		this.r = r;
	}

	@Override
	public void linkDetected(String title, String label, String target) {
		// Normalize the link text.
		try {
			StringBuilder buf = null;
			tokenizer.reset(new StringReader(label));
			stream.reset();
			while (stream.incrementToken()) {
				if (termAtt.length() <= 0)
					continue;
				if (buf == null)
					buf = new StringBuilder();
				else
					buf.append(' ');
				buf.append(termAtt.buffer(), 0, termAtt.length());
			}
			if (buf == null)
				return;
			label = buf.toString();
			CounterSet<String> seen = links.get(label);
			if (seen == null) {
				seen = new CounterSet<>();
				links.put(label, seen);
			}
			seen.count(target);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void close() {
		System.err.format("Closing %s output.\n", getClass().getSimpleName());
		PrintStream writer = null;
		try {
			writer = Util.openOutput(out);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		// We sort everything here. This is expensive, but makes the output
		// files nicer to use in the future.
		ArrayList<String> keys = new ArrayList<>(links.keySet());
		Collections.sort(keys);
		ResolveRedirectsFilter resolveRedirects = new ResolveRedirectsFilter(
				r.transitiveClosure());
		for (String key : keys) {
			filter.reset();
			CounterSet<String> counter = links.get(key);
			resolveRedirects.map = counter;
			counter.retainEntries(resolveRedirects);
			counter.retainEntries(filter);
			if (counter.size() == 0)
				continue;
			writer.append(key).append('\t')
					.append(Integer.toString(filter.getSum()));
			for (CounterSet.Entry<String> val : counter.descending()) {
				int c = val.getCount();
				if (c * 10 < filter.getMax())
					continue;
				writer.append('\t').append(val.getKey());
				writer.append(':').append(Integer.toString(c));
			}
			writer.append('\n');
		}
		if (writer != System.out)
			writer.close();
	}

	/**
	 * Filter to resolve redirects.
	 * 
	 * @author Erich Schubert
	 */
	public static final class ResolveRedirectsFilter implements
			TObjectIntProcedure<Object> {
		Map<String, String> redirects;
		TObjectIntHashMap<String> map;

		public ResolveRedirectsFilter(Map<String, String> redirects) {
			this.redirects = redirects;
		}

		@Override
		public boolean execute(Object target, int count) {
			String t = redirects.get(target);
			if (t == null)
				return true;
			map.adjustValue(t, count);
			return false;
		}
	}
}
