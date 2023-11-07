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

package org.eclipse.mylyn.internal.wikitext.ui.registry;

import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.mylyn.internal.wikitext.ui.WikiTextUiPlugin;
import org.eclipse.mylyn.wikitext.parser.markup.MarkupLanguage;
import org.eclipse.mylyn.wikitext.validation.MarkupValidator;
import org.eclipse.mylyn.wikitext.validation.ValidationRule;
import org.eclipse.mylyn.wikitext.validation.ValidationRules;

public class WikiTextExtensionPointReader {

	private static final String EXTENSION_POINT_NAMESPACE = "org.eclipse.mylyn.wikitext.ui"; //$NON-NLS-1$

	private static final String EXTENSION_MARKUP_LANGUAGE = "markupLanguage"; //$NON-NLS-1$

	private static final String EXTENSION_VALIDATION_RULES = "markupValidationRule"; //$NON-NLS-1$

	private static Object instanceLock = new Object();

	private static WikiTextExtensionPointReader instance;

	private SortedMap<String, Class<? extends MarkupLanguage>> languageByName;

	private Map<String, Class<? extends MarkupLanguage>> languageByFileExtension;

	private Map<Class<? extends MarkupLanguage>, String> languageNameByLanguage;

	private Map<String, String> languageExtensionByLanguage;

	private Map<String, ValidationRules> validationRulesByLanguageName;

	public static WikiTextExtensionPointReader instance() {
		synchronized (instanceLock) {
			if (instance == null) {
				instance = new WikiTextExtensionPointReader();
			}
			return instance;
		}
	}

	private WikiTextExtensionPointReader() {
		// prevent instantiation
	}

	/**
	 * Get a markup language by name.
	 *
	 * @param name
	 *                 the name of the markup language to retrieve
	 * @return the markup language or null if there is no markup language known by the given name
	 * @see #getMarkupLanguageNames()
	 */
	public MarkupLanguage getMarkupLanguage(String name) {
		if (languageByName == null) {
			initializeMarkupLanguages();
		}
		Class<? extends MarkupLanguage> languageClass = languageByName.get(name);
		if (languageClass == null) {
			// if not found by name, attempt to lookup by class name.
			for (Entry<String, Class<? extends MarkupLanguage>> entry : languageByName.entrySet()) {
				Class<? extends MarkupLanguage> clazz = entry.getValue();
				if (clazz.getName().equals(name)) {
					languageClass = clazz;
					name = entry.getKey();
					break;
				}
			}
		}
		if (languageClass != null) {
			return instantiateMarkupLanguage(name, languageClass);
		}
		return null;
	}

	private MarkupLanguage instantiateMarkupLanguage(String name, Class<? extends MarkupLanguage> languageClass) {
		try {
			MarkupLanguage language = languageClass.getConstructor().newInstance();
			language.setName(name);
			language.setExtendsLanguage(languageExtensionByLanguage.get(name));
			configureFileExtensions(language);
			return language;
		} catch (Exception e) {
			log(IStatus.ERROR, MessageFormat.format(Messages.getString("WikiTextExtensionPointReader.2"), name, //$NON-NLS-1$
					languageClass.getName(), e.getMessage()), e);
		}
		return null;
	}

	private void configureFileExtensions(MarkupLanguage language) {
		Set<String> fileExtensions = new HashSet<>();
		for (Entry<String, Class<? extends MarkupLanguage>> entry : languageByFileExtension.entrySet()) {
			if (entry.getValue() == language.getClass()) {
				fileExtensions.add(entry.getKey());
			}
		}
		if (!fileExtensions.isEmpty()) {
			language.setFileExtensions(fileExtensions);
		}
	}

	/**
	 * Get a markup language name for a file. A markup language is selected based on the registered languages and their
	 * expected file extensions.
	 *
	 * @param name
	 *                 the name of the file for which a markup language is desired
	 * @return the markup language name, or null if no markup language is registered for the specified file name
	 * @see #getMarkupLanguageForFilename(String)
	 */
	public String getMarkupLanguageNameForFilename(String name) {
		if (languageByFileExtension == null) {
			initializeMarkupLanguages();
		}
		int lastIndexOfDot = name.lastIndexOf('.');
		String extension = lastIndexOfDot == -1 ? name : name.substring(lastIndexOfDot + 1);
		extension = extension.toLowerCase();
		Class<? extends MarkupLanguage> languageClass = languageByFileExtension.get(extension);
		if (languageClass != null) {
			return languageNameByLanguage.get(languageClass);
		}
		return null;
	}

	/**
	 * Get the file extensions that are registered for markup languages. File extensions are specified without the
	 * leading dot.
	 */
	public Set<String> getMarkupFileExtensions() {
		if (languageByFileExtension == null) {
			initializeMarkupLanguages();
		}
		return Collections.unmodifiableSet(languageByFileExtension.keySet());
	}

	/**
	 * Get a markup language for a file. A markup language is selected based on the registered languages and their
	 * expected file extensions.
	 *
	 * @param name
	 *                 the name of the file for which a markup language is desired
	 * @return the markup language, or null if no markup language is registered for the specified file name
	 * @see #getMarkupLanguageForFilename(String)
	 */
	public MarkupLanguage getMarkupLanguageForFilename(String name) {
		if (languageByFileExtension == null) {
			initializeMarkupLanguages();
		}
		int lastIndexOfDot = name.lastIndexOf('.');
		String extension = lastIndexOfDot == -1 ? name : name.substring(lastIndexOfDot + 1);
		Class<? extends MarkupLanguage> languageClass = languageByFileExtension.get(extension);
		if (languageClass != null) {
			String languageName = null;
			for (Map.Entry<String, Class<? extends MarkupLanguage>> ent : languageByName.entrySet()) {
				if (ent.getValue() == languageClass) {
					languageName = ent.getKey();
					break;
				}
			}
			return instantiateMarkupLanguage(languageName, languageClass);
		}
		return null;
	}

	/**
	 * Get the names of all known markup languages
	 *
	 * @see #getMarkupLanguage(String)
	 */
	public Set<String> getMarkupLanguageNames() {
		if (languageByName == null) {
			initializeMarkupLanguages();
		}
		return languageByName.keySet();
	}

	/**
	 * Get a markup validator by language name.
	 *
	 * @param name
	 *                 the name of the markup language for which a validator is desired
	 * @return the markup validator
	 * @see #getMarkupLanguageNames()
	 */
	public MarkupValidator getMarkupValidator(String name) {
		MarkupValidator markupValidator = new MarkupValidator();

		if (validationRulesByLanguageName == null) {
			initializeValidationRules();
		}
		ValidationRules rules = validationRulesByLanguageName.get(name);
		if (rules != null) {
			markupValidator.getRules().addAll(rules.getRules());
		}

		return markupValidator;
	}

	private void initializeValidationRules() {
		initializeMarkupLanguages();
		synchronized (this) {
			if (validationRulesByLanguageName == null) {
				Map<String, ValidationRules> validationRulesByLanguageName = new HashMap<>();

				IExtensionPoint extensionPoint = Platform.getExtensionRegistry()
						.getExtensionPoint(getExtensionPointNamespace(), EXTENSION_VALIDATION_RULES);
				if (extensionPoint != null) {
					IConfigurationElement[] configurationElements = extensionPoint.getConfigurationElements();
					for (IConfigurationElement element : configurationElements) {
						try {
							String markupLanguage = element.getAttribute("markupLanguage"); //$NON-NLS-1$
							if (markupLanguage == null || markupLanguage.length() == 0) {
								throw new Exception(Messages.getString("WikiTextExtensionPointReader.4")); //$NON-NLS-1$
							}
							if (!languageByName.containsKey(markupLanguage)) {
								throw new Exception(MessageFormat
										.format(Messages.getString("WikiTextExtensionPointReader.5"), languageByName)); //$NON-NLS-1$
							}
							Object extension;
							try {
								extension = element.createExecutableExtension("class"); //$NON-NLS-1$
							} catch (CoreException e) {
								getLog().log(e.getStatus());
								continue;
							}
							if (!(extension instanceof ValidationRule)) {
								throw new Exception(MessageFormat.format(
										Messages.getString("WikiTextExtensionPointReader.7"), extension.getClass() //$NON-NLS-1$
												.getName()));
							}
							ValidationRules rules = validationRulesByLanguageName.get(markupLanguage);
							if (rules == null) {
								rules = new ValidationRules();
								validationRulesByLanguageName.put(markupLanguage, rules);
							}
							rules.addValidationRule((ValidationRule) extension);
						} catch (Exception e) {
							log(IStatus.ERROR,
									MessageFormat.format(Messages.getString("WikiTextExtensionPointReader.8"), //$NON-NLS-1$
											element.getDeclaringExtension().getContributor().getName(),
											EXTENSION_VALIDATION_RULES, e.getMessage()),
									e);
						}
					}
				}

				// now that we have the basic validation rules, check for language extensions and connect the hierarchy

				// first ensure that all language names have templates defined
				Set<String> languageNames = getMarkupLanguageNames();
				for (String languageName : languageNames) {
					validationRulesByLanguageName.computeIfAbsent(languageName, s -> new ValidationRules());
				}
				// next connect the hierarchy
				for (String languageName : languageNames) {
					MarkupLanguage markupLanguage = getMarkupLanguage(languageName);
					if (markupLanguage != null && markupLanguage.getExtendsLanguage() != null) {
						ValidationRules languageRules = validationRulesByLanguageName.get(languageName);
						ValidationRules parentLanguageRules = validationRulesByLanguageName
								.get(markupLanguage.getExtendsLanguage());

						languageRules.setParent(parentLanguageRules);
					}
				}

				this.validationRulesByLanguageName = validationRulesByLanguageName;
			}
		}
	}

	private void initializeMarkupLanguages() {
		synchronized (this) {
			if (this.languageByName == null) {
				SortedMap<String, Class<? extends MarkupLanguage>> markupLanguageByName = new TreeMap<>();
				Map<String, Class<? extends MarkupLanguage>> languageByFileExtension = new HashMap<>();
				Map<String, String> languageExtensionByLanguage = new HashMap<>();
				Map<Class<? extends MarkupLanguage>, String> languageNameByLanguage = new HashMap<>();

				IExtensionPoint extensionPoint = Platform.getExtensionRegistry()
						.getExtensionPoint(getExtensionPointNamespace(), EXTENSION_MARKUP_LANGUAGE);
				if (extensionPoint != null) {
					IConfigurationElement[] configurationElements = extensionPoint.getConfigurationElements();
					for (IConfigurationElement element : configurationElements) {
						String name = element.getAttribute("name"); //$NON-NLS-1$
						if (name == null || name.length() == 0) {
							log(IStatus.ERROR,
									MessageFormat.format(
											EXTENSION_MARKUP_LANGUAGE
													+ Messages.getString("WikiTextExtensionPointReader.10"), //$NON-NLS-1$
											element.getDeclaringExtension().getContributor().getName()));
							continue;
						}
						String extendsLanguage = element.getAttribute("extends"); //$NON-NLS-1$
						Object markupLanguage;
						try {
							markupLanguage = element.createExecutableExtension("class"); //$NON-NLS-1$
						} catch (CoreException e) {
							getLog().log(e.getStatus());
							continue;
						}
						if (!(markupLanguage instanceof MarkupLanguage d)) {
							log(IStatus.ERROR,
									MessageFormat.format(Messages.getString("WikiTextExtensionPointReader.13"), //$NON-NLS-1$
											markupLanguage.getClass().getName()));
							continue;
						}
						{
							Class<? extends MarkupLanguage> previous = markupLanguageByName.put(name, d.getClass());
							if (previous != null) {
								log(IStatus.ERROR, MessageFormat.format(
										EXTENSION_MARKUP_LANGUAGE
												+ Messages.getString("WikiTextExtensionPointReader.14"), //$NON-NLS-1$
										name, element.getDeclaringExtension().getContributor().getName(), name));
								markupLanguageByName.put(name, previous);
								continue;
							} else {
								languageNameByLanguage.put(d.getClass(), name);
							}
						}
						if (extendsLanguage != null) {
							languageExtensionByLanguage.put(name, extendsLanguage);
						}
						String fileExtensions = element.getAttribute("fileExtensions"); //$NON-NLS-1$
						if (fileExtensions != null) {
							String[] parts = fileExtensions.split("\\s*,\\s*"); //$NON-NLS-1$
							for (String part : parts) {
								if (part.length() != 0) {
									part = part.toLowerCase();
									Class<? extends MarkupLanguage> previous = languageByFileExtension.put(part,
											d.getClass());
									if (previous != null) {
										log(IStatus.ERROR, MessageFormat.format(
												EXTENSION_MARKUP_LANGUAGE
														+ Messages.getString("WikiTextExtensionPointReader.17"), //$NON-NLS-1$
												part, element.getDeclaringExtension().getContributor().getName(),
												part));
										languageByFileExtension.put(part, previous);
										continue;
									}
								}
							}

						}
					}
				}

				this.languageByFileExtension = languageByFileExtension;
				this.languageByName = markupLanguageByName;
				this.languageExtensionByLanguage = languageExtensionByLanguage;
				this.languageNameByLanguage = languageNameByLanguage;
			}
		}
	}

	private ILog getLog() {
		return WikiTextUiPlugin.getDefault().getLog();
	}

	private void log(int severity, String message) {
		log(severity, message, null);
	}

	private void log(int severity, String message, Throwable t) {
		getLog().log(new Status(severity, WikiTextUiPlugin.getDefault().getPluginId(), message, t));
	}

	private String getExtensionPointNamespace() {
		return EXTENSION_POINT_NAMESPACE;
	}

}
