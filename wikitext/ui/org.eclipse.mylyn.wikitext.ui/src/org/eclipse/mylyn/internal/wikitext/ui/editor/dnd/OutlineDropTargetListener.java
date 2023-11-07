/*******************************************************************************
 * Copyright (c) 2009, 2021 David Green and others.
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

package org.eclipse.mylyn.internal.wikitext.ui.editor.dnd;

import java.util.List;

import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.util.LocalSelectionTransfer;
import org.eclipse.jface.util.SafeRunnable;
import org.eclipse.jface.util.TransferDropTargetListener;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.mylyn.internal.wikitext.ui.WikiTextUiPlugin;
import org.eclipse.mylyn.internal.wikitext.ui.editor.operations.AbstractDocumentCommand;
import org.eclipse.mylyn.internal.wikitext.ui.editor.operations.CommandManager;
import org.eclipse.mylyn.internal.wikitext.ui.editor.operations.InsertLocation;
import org.eclipse.mylyn.internal.wikitext.ui.editor.operations.MoveSectionsCommand;
import org.eclipse.mylyn.wikitext.parser.outline.OutlineItem;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.PlatformUI;

/**
 * @author David Green
 */
public class OutlineDropTargetListener implements TransferDropTargetListener {

	private static final double THRESHOLD_BEFORE = 0.25f;

	private static final double THRESHOLD_AFTER = 0.75f;

	private final CommandManager commandManager;

	public OutlineDropTargetListener(CommandManager commandManager) {
		this.commandManager = commandManager;
	}

	@Override
	public void dragEnter(DropTargetEvent event) {
		// ignore
	}

	@Override
	public void dragLeave(DropTargetEvent event) {
		// ignore
	}

	@Override
	public void dragOperationChanged(DropTargetEvent event) {
		// ignore
	}

	@Override
	public void dropAccept(DropTargetEvent event) {
		// ignore
	}

	@Override
	public void dragOver(DropTargetEvent event) {
		// Based on XMLContentOutlineConfiguration
		event.feedback = DND.FEEDBACK_SELECT;
		float relativeLocation = computeRelativeLocation(event);
		if (relativeLocation > THRESHOLD_AFTER) {
			// point was mostly below the item
			event.feedback = DND.FEEDBACK_INSERT_AFTER;
		} else if (relativeLocation < THRESHOLD_BEFORE) {
			// point was mostly above the item
			event.feedback = DND.FEEDBACK_INSERT_BEFORE;
		}
		// always provide expand/scroll capability when dragging
		event.feedback |= DND.FEEDBACK_EXPAND | DND.FEEDBACK_SCROLL;
	}

	@Override
	public void drop(DropTargetEvent event) {
		List<OutlineItem> dropItems = getDropItems(event);
		if (dropItems != null) {
			InsertLocation location = computeInsertLocation(event);
			Object targetData = event.item.getData();

			final AbstractDocumentCommand command = computeCommand(targetData, dropItems, location);
			if (command != null) {
				if (command.isEnabled()) {
					SafeRunnable.run(new ISafeRunnable() {
						@Override
						public void handleException(Throwable exception) {
							WikiTextUiPlugin.getDefault().log(exception);
							MessageDialog.openError(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
									Messages.OutlineDropTargetListener_0, NLS.bind(Messages.OutlineDropTargetListener_1,
											new Object[] { exception.getMessage() }));
						}

						@Override
						public void run() throws Exception {
							commandManager.perform(command);
						}
					});
				} else {
					MessageDialog.openInformation(PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell(),
							Messages.OutlineDropTargetListener_2,
							NLS.bind(Messages.OutlineDropTargetListener_3, new Object[] { command.getProblemText() }));
				}
			}
		}
	}

	private AbstractDocumentCommand computeCommand(Object targetData, List<OutlineItem> dropItems,
			InsertLocation location) {
		return new MoveSectionsCommand((OutlineItem) targetData, dropItems, location);
	}

	/**
	 * get the outline items being dropped, or null if there are none or if the event does not qualify for a drop.
	 */
	@SuppressWarnings("unchecked")
	private List<OutlineItem> getDropItems(DropTargetEvent event) {
		if (event.operations == DND.DROP_NONE || event.item == null) {
			return null;
		}
		Object targetData = event.item.getData();
		if (!(targetData instanceof OutlineItem)) {
			return null;
		}

		ISelection selection = LocalSelectionTransfer.getTransfer().getSelection();
		if (selection instanceof IStructuredSelection structuredSelection && !selection.isEmpty()) {
			List<?> list = structuredSelection.toList();
			if (!list.isEmpty()) {
				for (Object i : list) {
					if (!(i instanceof OutlineItem)) {
						return null;
					}
				}
				return (List<OutlineItem>) list;
			}
		}
		return null;
	}

	private InsertLocation computeInsertLocation(DropTargetEvent event) {
		float relativeLocation = computeRelativeLocation(event);
		if (relativeLocation < THRESHOLD_BEFORE) {
			return InsertLocation.BEFORE;
		} else if (relativeLocation > THRESHOLD_AFTER) {
			return InsertLocation.AFTER;
		}
		return InsertLocation.WITHIN;
	}

	private float computeRelativeLocation(DropTargetEvent event) {
		if (event.item == null) {
			return 0.5f;
		} else if (event.item instanceof TreeItem treeItem) {
			Control control = treeItem.getParent();
			Point controlRelativeEventLocation = control.toControl(new Point(event.x, event.y));
			Rectangle bounds = treeItem.getBounds();
			return (float) (controlRelativeEventLocation.y - bounds.y) / (float) bounds.height;
		} else {
			return 0.0f;
		}
	}

	@Override
	public Transfer getTransfer() {
		return LocalSelectionTransfer.getTransfer();
	}

	@Override
	public boolean isEnabled(DropTargetEvent event) {
		return getTransfer().isSupportedType(event.currentDataType) && getDropItems(event) != null;
	}
}
