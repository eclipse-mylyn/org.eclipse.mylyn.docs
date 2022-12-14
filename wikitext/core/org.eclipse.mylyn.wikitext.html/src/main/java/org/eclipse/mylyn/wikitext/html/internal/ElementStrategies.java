/*******************************************************************************
 * Copyright (c) 2013, 2021 Tasktop Technologies and others.
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

package org.eclipse.mylyn.wikitext.html.internal;

import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.mylyn.wikitext.parser.Attributes;

abstract class ElementStrategies<ElementType extends Enum<ElementType>, ElementStrategy, HtmlElementStrategyType extends HtmlElementStrategy<ElementType>> {

	private Map<ElementType, ElementStrategy> elementStrategyByElementType;

	private final List<HtmlElementStrategyType> elementStrategies;

	ElementStrategies(Class<ElementType> elementTypeClass, Set<ElementType> elementTypes,
			List<HtmlElementStrategyType> elementStrategies) {
		requireNonNull(elementTypeClass);
		requireNonNull(elementTypes);
		this.elementStrategies = List.copyOf(requireNonNull(elementStrategies));

		initialize(elementTypeClass, elementTypes);
	}

	public ElementStrategy getStrategy(ElementType elementType, Attributes attributes) {
		requireNonNull(elementType);

		for (HtmlElementStrategyType strategy : elementStrategies) {
			if (strategy.matcher().matches(elementType, attributes)) {
				return getElementStrategy(strategy);
			}
		}
		return requireNonNull(elementStrategyByElementType.get(elementType));
	}

	abstract ElementStrategy getElementStrategy(HtmlElementStrategyType strategy);

	private void initialize(Class<ElementType> elementTypeClass, Set<ElementType> elementTypes) {
		Map<ElementType, ElementStrategy> elementStrategyByElementType = new HashMap<>();
		for (ElementType elementType : elementTypes) {
			addSupportedElementType(elementStrategyByElementType, elementType);
		}
		addImplicitElementTypes(elementStrategyByElementType, elementTypes);

		Map<ElementType, ElementStrategy> alternativesByElementType = new HashMap<>();
		for (ElementType elementType : EnumSet.allOf(elementTypeClass)) {
			if (!elementStrategyByElementType.containsKey(elementType)) {
				alternativesByElementType.put(elementType,
						calculateFallBackElementStrategy(elementStrategyByElementType, elementType));
			}
		}
		elementStrategyByElementType.putAll(alternativesByElementType);

		this.elementStrategyByElementType = Map.copyOf(elementStrategyByElementType);
	}

	abstract void addImplicitElementTypes(Map<ElementType, ElementStrategy> blockStrategyByElementType,
			Set<ElementType> elementTypes);

	void addSupportedElementType(Map<ElementType, ElementStrategy> elementStrategyByElementType,
			ElementType elementType) {
		elementStrategyByElementType.put(elementType, getSupportedStrategy(elementType));
	}

	abstract ElementStrategy getSupportedStrategy(ElementType elementType);

	abstract ElementStrategy getUnsupportedElementStrategy(ElementType elementType);

	abstract ElementStrategy createSubstitutionElementStrategy(ElementType alternative);

	abstract Map<ElementType, List<ElementType>> getElementTypeToAlternatives();

	private ElementStrategy calculateFallBackElementStrategy(Map<ElementType, ElementStrategy> strategies,
			ElementType elementType) {
		ElementStrategy elementStrategy = null;
		List<ElementType> alternatives = getElementTypeToAlternatives().get(elementType);
		if (alternatives != null) {
			for (ElementType alternative : alternatives) {
				if (strategies.containsKey(alternative)) {
					elementStrategy = createSubstitutionElementStrategy(alternative);
					break;
				}
			}
		}
		if (elementStrategy == null) {
			elementStrategy = getUnsupportedElementStrategy(elementType);
		}
		return elementStrategy;
	}

}
