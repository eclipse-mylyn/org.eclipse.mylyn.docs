/*******************************************************************************
 * Copyright (c) 2015 David Green.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     David Green - initial API and implementation
 *******************************************************************************/

package org.eclipse.mylyn.internal.wikitext.commonmark.inlines;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.StringWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.mylyn.internal.wikitext.commonmark.Line;
import org.eclipse.mylyn.internal.wikitext.commonmark.ProcessingContext;
import org.eclipse.mylyn.internal.wikitext.commonmark.ProcessingContext.NamedUriWithTitle;
import org.eclipse.mylyn.wikitext.core.parser.DocumentBuilder;
import org.eclipse.mylyn.wikitext.core.parser.builder.HtmlDocumentBuilder;

import com.google.common.base.CharMatcher;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;

public class PotentialBracketEndDelimiter extends InlineWithText {

	private static final Pattern HTML_ENTITY_PATTERN = Pattern.compile("(&([a-zA-Z][a-zA-Z0-9]{1,32}|#x[a-fA-F0-9]{1,8}|#[0-9]{1,8});)");

	private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#([0-9]{1,8});");

	static final String ESCAPABLE_CHARACTER_GROUP = "[!\"\\\\#$%&'()*+,./:;<=>?@\\[\\]^_`{|}~-]";

	static final String ESCAPED_CHARS = "(?:\\\\" + ESCAPABLE_CHARACTER_GROUP + ")";

	static final String CAPTURING_ESCAPED_CHARS = "\\\\(" + ESCAPABLE_CHARACTER_GROUP + ")";

	static final String PARENS_TITLE_PART = "(?:\\(((?:" + ESCAPED_CHARS + "|[^\\)])*)\\))";

	static final String SINGLE_QUOTED_TITLE_PART = "(?:'((?:" + ESCAPED_CHARS + "|[^'])*)')";

	static final String QUOTED_TITLE_PART = "(?:\"((?:" + ESCAPED_CHARS + "|[^\"])*)\")";

	static final String BRACKET_URI_PART = "<((?:[^<>\\\\\r\n]|" + ESCAPED_CHARS + ")*?)>";

	private static final String IN_PARENS = "\\((?:[^\\\\()]|" + ESCAPED_CHARS + ")*\\)";

	static final String NOBRACKET_URI_PART = "((?:[^\\\\\\s()]|" + ESCAPED_CHARS + "|" + IN_PARENS + ")+)";

	static final String URI_PART = "(?:" + BRACKET_URI_PART + "|" + NOBRACKET_URI_PART + ")";

	static final String TITLE_PART = "(?:" + QUOTED_TITLE_PART + "|" + SINGLE_QUOTED_TITLE_PART + "|"
			+ PARENS_TITLE_PART + ")";

	final Pattern endPattern = Pattern.compile("\\(\\s*" + URI_PART + "?(?:\\s+" + TITLE_PART + ")?\\s*\\)(.*)",
			Pattern.DOTALL);

	final Pattern referenceLabelPattern = Pattern.compile("(\\s*\\[((?:[^\\]]|\\\\]){0,1000})]).*", Pattern.DOTALL);

	final Pattern referenceDefinitionEndPattern = Pattern.compile(":\\s*" + URI_PART + "?(?:\\s+" + TITLE_PART
			+ ")?\\s*(.*)", Pattern.DOTALL);

	public PotentialBracketEndDelimiter(Line line, int offset) {
		super(line, offset, 1, "]");
	}

	@Override
	public void emit(DocumentBuilder builder) {
		builder.characters(text);
	}

	@Override
	public void apply(ProcessingContext context, List<Inline> inlines, Cursor cursor) {
		Optional<PotentialBracketDelimiter> previousDelimiter = findLastPotentialBracketDelimiter(inlines);
		if (previousDelimiter.isPresent()) {
			PotentialBracketDelimiter openingDelimiter = previousDelimiter.get();
			int indexOfOpeningDelimiter = inlines.indexOf(openingDelimiter);

			boolean referenceDefinition = cursor.hasNext() && cursor.getNext() == ':'
					&& eligibleForReferenceDefinition(openingDelimiter, cursor);
			Matcher matcher = cursor.hasNext() ? cursor.matcher(1, referenceDefinition
					? referenceDefinitionEndPattern
					: endPattern) : null;

			List<Inline> contents = InlineParser.secondPass(inlines.subList(indexOfOpeningDelimiter + 1, inlines.size()));
			if (!openingDelimiter.isLinkDelimiter() || !containsLink(contents)) {

				if (!cursor.hasNext() || !checkNotNull(matcher).matches()) {
					String referenceName = toReferenceName(referenceName(cursor, contents));
					int size = 1;
					if (cursor.hasNext()) {
						Matcher referenceLabelMatcher = cursor.matcher(1, referenceLabelPattern);
						if (referenceLabelMatcher.matches()) {
							String label = referenceLabelMatcher.group(2);
							if (!label.isEmpty()) {
								referenceName = toReferenceName(label);
							}
							size = referenceLabelMatcher.end(1) - referenceLabelMatcher.start(1) + 1;
						}
					}
					NamedUriWithTitle uriWithTitle = referenceName == null
							? null
							: context.namedUriWithTitle(referenceName);
					if (uriWithTitle != null) {
						cursor.advance(size);

						truncate(inlines, indexOfOpeningDelimiter);

						int length = getOffset() - openingDelimiter.getOffset();
						if (openingDelimiter.isLinkDelimiter()) {
							inlines.add(new Link(openingDelimiter.getLine(), openingDelimiter.getOffset(), length,
									uriWithTitle.getUri(), uriWithTitle.getTitle(), contents));
						} else {
							inlines.add(new Image(openingDelimiter.getLine(), openingDelimiter.getOffset(), length,
									uriWithTitle.getUri(), uriWithTitle.getTitle(), contents));
						}
						return;
					}
				} else {
					String uri = linkUri(matcher);
					String title = linkTitle(matcher);

					if (!(referenceDefinition && (Strings.isNullOrEmpty(uri) || hasContentOnSameLine(matcher, cursor)))) {
						String referenceName = null;
						if (referenceDefinition) {
							referenceName = toReferenceName(referenceName(cursor, contents));
						}
						int closingLength = matcher.start(6) - matcher.start() + 1;
						cursor.advance(closingLength);
						int length = getOffset() - openingDelimiter.getOffset() + closingLength;

						truncate(inlines, indexOfOpeningDelimiter);

						if (referenceDefinition) {
							truncatePrecedingWhitespace(inlines, 3);
							inlines.add(new ReferenceDefinition(openingDelimiter.getLine(),
									openingDelimiter.getOffset(), length, uri, title, referenceName));
						} else if (openingDelimiter.isImageDelimiter()) {
							inlines.add(new Image(openingDelimiter.getLine(), openingDelimiter.getOffset(), length,
									uri, title, contents));
						} else {
							inlines.add(new Link(openingDelimiter.getLine(), openingDelimiter.getOffset(), length, uri,
									title, contents));
						}
						return;
					}
				}
			}
			replaceDelimiter(inlines, indexOfOpeningDelimiter, openingDelimiter);
		}
		applyCharacters(context, inlines, cursor);
	}

	private String referenceName(Cursor cursor, List<Inline> contents) {
		if (contents.isEmpty()) {
			return "";
		}
		int start = cursor.toCursorOffset(contents.get(0).getOffset());
		int end = cursor.toCursorOffset(getOffset());
		return cursor.getText(start, end);
	}

	private boolean containsLink(List<Inline> contents) {
		for (Inline inline : contents) {
			if (inline instanceof Link) {
				return true;
			} else if (inline instanceof InlineWithNestedContents
					&& containsLink(((InlineWithNestedContents) inline).getContents())) {
				return true;
			}
		}
		return false;
	}

	private void replaceDelimiter(List<Inline> inlines, int index, PotentialBracketDelimiter delimiter) {
		inlines.set(index,
				new Characters(delimiter.getLine(), delimiter.getOffset(), delimiter.getLength(), delimiter.getText()));
	}

	private boolean hasContentOnSameLine(Matcher matcher, Cursor cursor) {
		int indexOfContent = matcher.start(6);
		if (indexOfContent == -1 || matcher.end(6) == indexOfContent) {
			return false;
		}
		int startIndex = titleEndIndex(matcher);
		if (startIndex == 0) {
			startIndex = matcher.end(3);
			if (startIndex == -1) {
				startIndex = matcher.end(2);
			}
		}
		if (startIndex > 0) {
			for (int x = startIndex; x < indexOfContent; ++x) {
				char c = cursor.getChar(x);
				if (c == '\n') {
					return false;
				}
				if (!Character.isWhitespace(c)) {
					return false;
				}
			}
			return true;
		}
		return false;
	}

	private void truncatePrecedingWhitespace(List<Inline> inlines, int length) {
		if (!inlines.isEmpty()) {
			Inline last = inlines.get(inlines.size() - 1);
			if (last instanceof Characters) {
				Characters characters = (Characters) last;
				if (characters.getText().length() <= length
						&& CharMatcher.WHITESPACE.matchesAllOf(characters.getText())) {
					inlines.remove(inlines.size() - 1);
				}
			}
		}
	}

	public void truncate(List<Inline> inlines, int indexOfOpeningDelimiter) {
		while (inlines.size() > indexOfOpeningDelimiter) {
			inlines.remove(indexOfOpeningDelimiter);
		}
	}

	boolean eligibleForReferenceDefinition(PotentialBracketDelimiter openingDelimiter, Cursor cursor) {
		boolean linkDelimiter = openingDelimiter.isLinkDelimiter();
		if (!linkDelimiter) {
			return false;
		}
		int cursorRelativeOffset = cursor.toCursorOffset(openingDelimiter.getOffset());
		for (int x = cursorRelativeOffset - 1; x >= 0; --x) {
			char c = cursor.getChar(x);
			if (c == '\n') {
				return true;
			} else if (c != ' ') {
				return false;
			}
			if (cursorRelativeOffset - x == 4) {
				return false;
			}
		}
		int cursorRelativeEndOffset = cursor.toCursorOffset(getOffset());
		for (int x = cursorRelativeOffset + 1; x < cursorRelativeEndOffset; ++x) {
			char c = cursor.getChar(x);
			if (c == '[' && !precededByBackslashEscape(cursor, x)) {
				return false;
			}
		}
		return true;
	}

	boolean precededByBackslashEscape(Cursor cursor, int originalOffset) {
		int count = 0;
		for (int index = originalOffset - 1; index >= 0; --index) {
			char c = cursor.getChar(index);
			if (c == '\\') {
				++count;
			} else {
				break;
			}
		}
		return count % 2 == 1;
	}

	private void applyCharacters(ProcessingContext context, List<Inline> inlines, Cursor cursor) {
		new Characters(getLine(), getOffset(), getLength(), getText()).apply(context, inlines, cursor);
	}

	private String linkTitle(Matcher matcher) {
		String title = matcher.group(3);
		if (title == null) {
			title = matcher.group(4);
			if (title == null) {
				title = matcher.group(5);
				if (title == null) {
					title = "";
				}
			}
		}
		String titleWithoutBackslashEscapes = unescapeBackslashEscapes(title);
		return replaceHtmlEntities(titleWithoutBackslashEscapes, null);
	}

	private int titleEndIndex(Matcher matcher) {
		int index = matcher.end(3);
		if (index == -1) {
			index = matcher.end(4);
			if (index == -1) {
				index = matcher.end(5);
			}
		}
		return index + 1;
	}

	private String linkUri(Matcher matcher) {
		String uriWithEscapes = matcher.group(1);
		if (uriWithEscapes == null) {
			uriWithEscapes = matcher.group(2);
		}
		uriWithEscapes = Objects.firstNonNull(uriWithEscapes, "");
		return normalizeUri(uriWithEscapes);
	}

	private String normalizeUri(String uriWithEscapes) {
		String uriWithoutBackslashEscapes = unescapeBackslashEscapes(uriWithEscapes);
		try {
			String uriWithoutHtmlEntities = replaceHtmlEntities(uriWithoutBackslashEscapes,
					UrlEscapers.urlFormParameterEscaper());
			String decoded = URLDecoder.decode(uriWithoutHtmlEntities, StandardCharsets.UTF_8.name());
			Escaper escaper = UrlEscapers.urlFragmentEscaper();
			return escaper.escape(decoded);
		} catch (Exception e) {
			return uriWithoutBackslashEscapes;
		}
	}

	String replaceHtmlEntities(String text, Escaper escaper) {
		String replaced = "";
		int lastEnd = 0;
		Matcher matcher = HTML_ENTITY_PATTERN.matcher(text);
		while (matcher.find()) {
			if (lastEnd < matcher.start(1)) {
				replaced += text.substring(lastEnd, matcher.start(1));
			}
			String entity = matcher.group(2);
			String numericEntity = entityToNumericEquivalent(entity);
			String replacement = numericEntityReplacement(numericEntity, escaper);
			replaced += replacement == null ? matcher.group(1) : replacement;
			lastEnd = matcher.end(1);
		}
		if (lastEnd < text.length()) {
			replaced += text.substring(lastEnd, text.length());
		}
		return replaced;
	}

	private String numericEntityReplacement(String numericEntity, Escaper escaper) {
		Matcher numericEntityMatcher = NUMERIC_ENTITY_PATTERN.matcher(numericEntity);
		if (numericEntityMatcher.matches()) {
			char c;
			try {
				c = (char) Integer.parseInt(numericEntityMatcher.group(1));
			} catch (NumberFormatException e) {
				return null;
			}
			String replacement = Character.toString(c);
			return escaper == null ? replacement : escaper.escape(replacement);
		}
		return null;
	}

	String entityToNumericEquivalent(String entity) {
		StringWriter out = new StringWriter();
		HtmlDocumentBuilder builder = new HtmlDocumentBuilder(out);
		builder.setEmitAsDocument(false);
		builder.setFilterEntityReferences(true);
		builder.entityReference(entity);
		builder.flush();
		return out.toString();
	}

	String toReferenceName(String stringWithBackslashEscapes) {
		return stringWithBackslashEscapes.replaceAll("(?s)\\\\(\\[|\\])", "$1").replaceAll("\\s+", " ");
	}

	String unescapeBackslashEscapes(String stringWithBackslashEscapes) {
		return stringWithBackslashEscapes.replaceAll(CAPTURING_ESCAPED_CHARS, "$1");
	}

	private Optional<PotentialBracketDelimiter> findLastPotentialBracketDelimiter(List<Inline> inlines) {
		for (int x = inlines.size() - 1; x >= 0; --x) {
			Inline inline = inlines.get(x);
			if (inline instanceof PotentialBracketDelimiter) {
				PotentialBracketDelimiter delimiter = (PotentialBracketDelimiter) inline;
				return Optional.of(delimiter);
			}
		}
		return Optional.absent();
	}
}