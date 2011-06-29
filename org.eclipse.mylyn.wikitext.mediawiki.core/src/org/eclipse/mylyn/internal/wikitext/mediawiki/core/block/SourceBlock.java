/*******************************************************************************
 * Copyright (c) 2011 David Green and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     David Green - initial API and implementation
 *******************************************************************************/

package org.eclipse.mylyn.internal.wikitext.mediawiki.core.block;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.mylyn.wikitext.core.parser.Attributes;
import org.eclipse.mylyn.wikitext.core.parser.DocumentBuilder.BlockType;
import org.eclipse.mylyn.wikitext.core.parser.markup.block.AbstractHtmlBlock;

/**
 * A block that implements the syntax highlighting extension
 * 
 * @author David Green
 */
public class SourceBlock extends AbstractHtmlBlock {

	public SourceBlock() {
		super("source"); //$NON-NLS-1$
	}

	private String lang;

	// REMOVED IN 1.6
	@Override
	protected void setAttributes(String attributes) {
		if (attributes != null) {
			Pattern pattern = Pattern.compile("\\s+([a-zA-Z][a-zA-Z0-9_:-]*)=\"([^\"]*)\""); //$NON-NLS-1$
			Matcher matcher = pattern.matcher(attributes);
			while (matcher.find()) {
				String attrName = matcher.group(1);
				String attrValue = matcher.group(2);
				handleAttribute(attrName, attrValue);
			}
		}
	}

	protected void handleAttribute(String attrName, String attrValue) {
		if (attrName.equalsIgnoreCase("lang")) { //$NON-NLS-1$
			lang = attrValue;
		}
	}

	@Override
	protected void beginBlock() {
		final Attributes attributes = new Attributes();
		if (lang != null) {
			attributes.setCssClass("source-" + lang); //$NON-NLS-1$
		}
		builder.beginBlock(BlockType.PREFORMATTED, attributes);
	}

	@Override
	protected void endBlock() {
		builder.endBlock();
	}

	@Override
	protected void handleBlockContent(String content) {
		if (content.length() > 0) {
			builder.characters(content);
		} else if (blockLineCount == 1) {
			return;
		}
		builder.characters("\n"); //$NON-NLS-1$
	}

}
