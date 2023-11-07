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

package org.eclipse.mylyn.internal.wikitext.ui.validation;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Region;
import org.eclipse.mylyn.internal.wikitext.ui.WikiTextUiPlugin;
import org.eclipse.mylyn.internal.wikitext.ui.editor.MarkupEditor;
import org.eclipse.mylyn.internal.wikitext.ui.editor.validation.ResourceMarkerMarkupValidator;
import org.eclipse.mylyn.wikitext.ui.WikiText;
import org.eclipse.mylyn.wikitext.validation.ValidationProblem;
import org.eclipse.osgi.util.NLS;

/**
 * A project builder that invokes validation on wikitext files
 *
 * @author David Green
 */
public class ValidationProjectBuilder extends IncrementalProjectBuilder {

	public static final String ID = "org.eclipse.mylyn.wikitext.ui.wikiTextValidationBuilder"; //$NON-NLS-1$

	private static final IProject[] NO_PROJECTS = new IProject[0];

	public ValidationProjectBuilder() {
	}

	@Override
	protected void clean(IProgressMonitor monitor) throws CoreException {
		getProject().deleteMarkers(ValidationProblem.DEFAULT_MARKER_ID, true, IResource.DEPTH_INFINITE);
	}

	@SuppressWarnings("rawtypes")
	@Override
	protected IProject[] build(int kind, Map args, IProgressMonitor monitor) throws CoreException {
		IProject project = getProject();
		IResourceDelta resourceDelta = getDelta(project);

		// find files that need validating.  We do this first so that we can accurately represent progress
		List<ValidationInfo> files = null;
		if ((kind != IncrementalProjectBuilder.INCREMENTAL_BUILD && kind != IncrementalProjectBuilder.AUTO_BUILD)
				|| resourceDelta == null) {
			files = collect(project, monitor);
		} else {
			files = collect(resourceDelta, monitor);
		}
		// validate if needed
		if (files != null && !files.isEmpty() && !isInterrupted() && !monitor.isCanceled()) {
			validate(files, monitor);
		}
		// be sure to cancel properly so that calling mechanisms will know
		if (monitor.isCanceled()) {
			throw new OperationCanceledException();
		}
		return NO_PROJECTS;
	}

	private static class ValidationInfo {
		private final IFile file;

		private final String languageName;

		public ValidationInfo(IFile file, String languageName) {
			this.file = file;
			this.languageName = languageName;
		}
	}

	/**
	 * collect resources for validation from a resource delta
	 */
	private List<ValidationInfo> collect(IResourceDelta resourceDelta, final IProgressMonitor monitor)
			throws CoreException {
		final List<ValidationInfo> files = new ArrayList<>();

		resourceDelta.accept(delta -> {
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			if (isInterrupted()) {
				return false;
			}
			IResource resource = delta.getResource();
			if (resource instanceof IFile file) {
				if ((delta.getKind() & (IResourceDelta.ADDED | IResourceDelta.CHANGED)) != 0) {
					ValidationProjectBuilder.this.visit(files, file);
				}
			} else if (resource instanceof IContainer container) {
				if (filtered(container)) {
					return false;
				}
			}
			return true;
		});
		return files;
	}

	private List<ValidationInfo> collect(IProject project, final IProgressMonitor monitor) throws CoreException {
		final List<ValidationInfo> files = new ArrayList<>();
		project.accept(resource -> {
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			if (isInterrupted()) {
				return false;
			}
			if (resource instanceof IFile file) {
				ValidationProjectBuilder.this.visit(files, file);
			} else if (resource instanceof IContainer container) {
				if (filtered(container)) {
					return false;
				}
			}
			return true;
		});
		return files;
	}

	private boolean filtered(IContainer container) {
		return false;
	}

	private void visit(final List<ValidationInfo> files, IFile file) {
		boolean derived = file.isDerived(IResource.CHECK_ANCESTORS);
		if (derived) {
			return;
		}
		// only validate files for which there is a known language
		String language = getMarkupLanguageForFile(file);
		if (language != null) {
			files.add(new ValidationInfo(file, language));
		}
	}

	private void validate(List<ValidationInfo> files, IProgressMonitor monitor) throws CoreException {
		if (files.isEmpty()) {
			return;
		}
		final int factor = 1000;
		monitor.beginTask(Messages.ValidationProjectBuilder_validationTask, files.size() * factor);
		for (ValidationInfo file : files) {
			if (monitor.isCanceled()) {
				throw new OperationCanceledException();
			}
			if (isInterrupted()) {
				break;
			}
			validate(file, SubMonitor.convert(monitor, factor));
		}
		monitor.done();
	}

	/**
	 * validate a single file
	 */
	private void validate(ValidationInfo file, IProgressMonitor monitor) throws CoreException {
		int totalWork = 1000;
		monitor.beginTask(
				NLS.bind(Messages.ValidationProjectBuilder_validatingFileTask, new Object[] { file.file.getName() }),
				totalWork);
		ResourceMarkerMarkupValidator validator = new ResourceMarkerMarkupValidator();
		validator.setMarkupLanguage(WikiText.getMarkupLanguage(file.languageName));
		validator.setResource(file.file);

		if (validator.getMarkupLanguage() != null) {
			StringWriter writer = new StringWriter();
			String charset = file.file.getCharset();
			try (Reader reader = charset == null
					? new InputStreamReader(new BufferedInputStream(file.file.getContents()))
					: new InputStreamReader(new BufferedInputStream(file.file.getContents()), charset)) {
				int c;
				while ((c = reader.read()) != -1) {
					writer.append((char) c);
				}
			} catch (IOException ioe) {
				throw new CoreException(WikiTextUiPlugin.getDefault().createStatus(IStatus.ERROR, ioe));
			}
			monitor.worked(totalWork / 2);
			IDocument document = new Document(writer.toString());
			validator.validate(SubMonitor.convert(monitor, totalWork / 2), document,
					new Region(0, document.getLength()));
		}
		monitor.done();
	}

	private String getMarkupLanguageForFile(IFile file) {
		String language = MarkupEditor.getMarkupLanguagePreference(file);
		if (language == null) {
			language = WikiText.getMarkupLanguageNameForFilename(file.getName());
		}
		return language;
	}
}
