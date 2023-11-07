/*******************************************************************************
 * Copyright (c) 2010, 2021 David Green and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     David Green - initial API and implementation
 *******************************************************************************/

package org.eclipse.mylyn.internal.wikitext.ui.editor.syntax;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.mylyn.wikitext.parser.Attributes;
import org.eclipse.mylyn.wikitext.parser.LinkAttributes;
import org.eclipse.mylyn.wikitext.parser.MarkupParser;
import org.eclipse.mylyn.wikitext.parser.builder.NoOpDocumentBuilder;
import org.eclipse.mylyn.wikitext.parser.markup.MarkupLanguage;

/**
 * A hyperlink detector that can detect hyperlinks in markup source.
 *
 * @author dgreen
 */
public class MarkupHyperlinkDetector implements IHyperlinkDetector {

	private MarkupLanguage markupLanguage;

	private IFile file;

	public MarkupHyperlinkDetector() {
	}

	public void setMarkupLanguage(MarkupLanguage markupLanguage) {
		this.markupLanguage = markupLanguage;
	}

	@Override
	public IHyperlink[] detectHyperlinks(ITextViewer textViewer, IRegion region, boolean canShowMultipleHyperlinks) {
		if (markupLanguage == null || file == null) {
			return null;
		}
		IDocument document = textViewer.getDocument();
		if (document == null || document.getLength() == 0) {
			return null;
		}

		String content;
		int contentOffset;
		try {
			if (region.getLength() == 0) {
				// expand the region to include the whole line
				IRegion lineInfo = document.getLineInformationOfOffset(region.getOffset());
				int lineLength = lineInfo.getLength();
				int lineOffset = lineInfo.getOffset();
				int lineEnd = lineOffset + lineLength;
				int regionEnd = region.getOffset() + region.getLength();
				if (lineOffset < region.getOffset()) {
					int regionLength = Math.max(regionEnd, lineEnd) - lineOffset;
					contentOffset = lineOffset;
					content = document.get(lineOffset, regionLength);
				} else {
					// the line starts after region, may never happen
					int regionLength = Math.max(regionEnd, lineEnd) - region.getOffset();
					contentOffset = region.getOffset();
					content = document.get(contentOffset, regionLength);
				}
			} else {
				content = document.get(region.getOffset(), region.getLength());
				contentOffset = region.getOffset();
			}
		} catch (BadLocationException ex) {
			return null;
		}
		MarkupParser markupParser = new MarkupParser(markupLanguage);
		final List<HyperlinkDescriptor> links = new ArrayList<>();
		markupParser.setBuilder(new NoOpDocumentBuilder() {
			@Override
			public void link(Attributes attributes, String hrefOrHashName, String text) {
				if (hrefOrHashName != null && !hrefOrHashName.startsWith("#")) { //$NON-NLS-1$
					IRegion region = createRegion();
					links.add(new HyperlinkDescriptor(hrefOrHashName, region));
				}
			}

			private IRegion createRegion() {
				int offset = getLocator().getLineCharacterOffset();
				int length = getLocator().getLineSegmentEndOffset() - offset;
				return new Region(offset, length);
			}

			@Override
			public void beginSpan(SpanType type, Attributes attributes) {
				if (type == SpanType.LINK) {
					if (attributes instanceof LinkAttributes linkAttributes) {
						if (linkAttributes.getHref() != null && !linkAttributes.getHref().startsWith("#")) { //$NON-NLS-1$
							IRegion region = createRegion();
							links.add(new HyperlinkDescriptor(linkAttributes.getHref(), region));
						}
					}
				}
			}
		});
		markupParser.parse(content);

		if (!links.isEmpty()) {
			List<IHyperlink> hyperlinks = new ArrayList<>(links.size());
			for (HyperlinkDescriptor descriptor : links) {
				if (descriptor.href.indexOf(':') == -1 && descriptor.href.length() > 1
						&& descriptor.href.charAt(0) != '/') {
					IRegion hyperlinkRegion = new Region(descriptor.region.getOffset() + contentOffset,
							descriptor.region.getLength());
					if (region.getLength() > 0) {
						if (!isInRegion(region, hyperlinkRegion)) {
							continue;
						}
					} else {
						if (!(hyperlinkRegion.getOffset() <= region.getOffset()
								&& (hyperlinkRegion.getOffset() + hyperlinkRegion.getLength()) >= region.getOffset())) {
							continue;
						}
					}
					try {
						IPath containerPath = file.getParent().getFullPath();
						IPath absolutePath = containerPath.append(descriptor.href);
						IFile targetFile = ResourcesPlugin.getWorkspace().getRoot().getFile(absolutePath);
						if (targetFile != null) {
							if (targetFile.exists()) {
								hyperlinks.add(new EditFileHyperlink(targetFile, hyperlinkRegion));
							}
							IContainer parent = targetFile.getParent();
							if (parent.exists()) {
								String nameNoExtension = targetFile.getName();
								if (nameNoExtension.indexOf('.') != -1) {
									nameNoExtension = nameNoExtension.substring(0,
											nameNoExtension.lastIndexOf('.') + 1);
								}
								IResource[] members = parent.members();
								for (IResource resource : members) {

									if (resource.getType() == IResource.FILE
											&& resource.getName().startsWith(nameNoExtension)
											&& !resource.equals(targetFile)) {
										hyperlinks.add(new EditFileHyperlink((IFile) resource, hyperlinkRegion));
									}
								}
							}
						}
					} catch (Throwable t) {
						//  ignore
					}
				}
			}
			if (!hyperlinks.isEmpty()) {
				return hyperlinks.toArray(new IHyperlink[hyperlinks.size()]);
			}
		}
		return null;
	}

	private boolean isInRegion(IRegion detectInRegion, IRegion hyperlinkRegion) {
		return detectInRegion.getOffset() >= hyperlinkRegion.getOffset()
				&& detectInRegion.getOffset() <= hyperlinkRegion.getOffset() + hyperlinkRegion.getLength();
	}

	public void setFile(IFile file) {
		this.file = file;
	}

	private static class HyperlinkDescriptor {
		String href;

		IRegion region;

		protected HyperlinkDescriptor(String href, IRegion region) {
			this.href = href;
			this.region = region;
		}

	}

}
