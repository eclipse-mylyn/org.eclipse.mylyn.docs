/*******************************************************************************
 * Copyright (c) 2007, 2021 David Green and others.
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
package org.eclipse.mylyn.internal.wikitext.ui.viewer;

import java.util.Iterator;

import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.DefaultTextHover;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextHoverExtension;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.ISourceViewerExtension2;
import org.eclipse.mylyn.wikitext.ui.annotation.AnchorHrefAnnotation;
import org.eclipse.mylyn.wikitext.ui.annotation.TitleAnnotation;
import org.eclipse.mylyn.wikitext.ui.viewer.HtmlTextPresenter;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.editors.text.EditorsUI;

/**
 * A text hover implementation that finds regions based on annotations, and supports HTML markup in the tooltip string.
 *
 * @author David Green
 */
public class TextHover extends DefaultTextHover implements ITextHoverExtension {
	private final ISourceViewer sourceViewer;

	public TextHover(ISourceViewer sourceViewer) {
		super(sourceViewer);
		this.sourceViewer = sourceViewer;
	}

	@Override
	protected boolean isIncluded(Annotation annotation) {
		if (annotation.getType().equals(TitleAnnotation.TYPE)
				|| annotation.getType().equals(AnchorHrefAnnotation.TYPE)) {
			return true;
		}
		return false;
	}

	@Override
	public IRegion getHoverRegion(ITextViewer textViewer, int offset) {
		IAnnotationModel annotationModel = sourceViewer.getAnnotationModel();
		if (annotationModel != null) {
			int start = Integer.MAX_VALUE;
			int end = -1;
			Iterator<?> iterator = annotationModel.getAnnotationIterator();
			while (iterator.hasNext()) {
				Annotation next = (Annotation) iterator.next();
				Position position = annotationModel.getPosition(next);
				if (position.getOffset() <= offset && (position.getLength() + position.getOffset()) >= offset) {
					start = Math.min(start, position.getOffset());
					end = Math.max(end, position.getOffset() + position.getLength());
				}
			}
			if (start <= end && end > -1) {
				return new Region(start, end - start);
			}
		}
		return super.getHoverRegion(textViewer, offset);
	}

	@Override
	public IInformationControlCreator getHoverControlCreator() {
		return parent -> {

			String tooltipAffordanceString = null;
			try {
				tooltipAffordanceString = EditorsUI.getTooltipAffordanceString();
			} catch (Exception e) {
				// expected in a non-eclipse environment
			}

			return new DefaultInformationControl(parent, new HtmlTextPresenter()) {
				@Override
				public void setLocation(Point location) {
					// prevent the location from being set to where the cursor is: otherwise the popup is displayed
					// and then hidden immediately.
					Point cursorLocation = Display.getCurrent().getCursorLocation();
					if (cursorLocation.y + 12 >= location.y) {
						location.y = cursorLocation.y + 13;
					}
					super.setLocation(location);
				}
			};
		};
	}

	private IAnnotationModel getAnnotationModel(ISourceViewer viewer) {
		if (viewer instanceof ISourceViewerExtension2 extension) {
			return extension.getVisualAnnotationModel();
		}
		return viewer.getAnnotationModel();
	}

	@SuppressWarnings({ "deprecation" })
	@Override
	public String getHoverInfo(ITextViewer textViewer, IRegion hoverRegion) {
		IAnnotationModel model = getAnnotationModel(sourceViewer);
		if (model == null) {
			return null;
		}

		Iterator<?> e = model.getAnnotationIterator();
		while (e.hasNext()) {
			Annotation a = (Annotation) e.next();
			if (isIncluded(a)) {
				Position p = model.getPosition(a);
				if (p != null && p.overlapsWith(hoverRegion.getOffset(), hoverRegion.getLength())) {
					String msg = a.getText();
					if (msg != null && msg.trim().length() > 0) {
						if (a.getType().equals(AnchorHrefAnnotation.TYPE)) {
							if (msg.startsWith("#")) { //$NON-NLS-1$
								// don't provide hover information for document-internal
								// links
								return null;
							} else {
								return NLS.bind(Messages.TextHover_hyperlinkHover, msg);
							}
						}
						return msg;
					}
				}
			}
		}

		return null;
	}
}
