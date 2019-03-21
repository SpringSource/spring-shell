/*
 * Copyright 2015-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.shell.legacy;

import java.lang.reflect.Parameter;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.shell.core.Completion;
import org.springframework.shell.core.Converter;
import org.springframework.shell.core.MethodTarget;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.shell.CompletionContext;
import org.springframework.shell.CompletionProposal;
import org.springframework.shell.ParameterDescription;
import org.springframework.shell.ParameterResolver;
import org.springframework.shell.ValueResult;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;

import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import javax.validation.metadata.MethodDescriptor;
import javax.validation.metadata.ParameterDescriptor;

/**
 * Resolves parameters by looking at the {@link CliOption} annotation and acting
 * accordingly.
 *
 * @author Eric Bottard
 * @author Camilo Gonzalez
 */
@Component
public class LegacyParameterResolver implements ParameterResolver {

	private static final String CLI_OPTION_NULL = "__NULL__";

	/**
	 * Prefix used by Spring Shell 1 for the argument keys (<em>e.g.</em> command --key
	 * value).
	 */
	private static final String CLI_PREFIX = "--";

	@Autowired(required = false)
	private Collection<Converter<?>> converters = new ArrayList<>();

	private Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Autowired(required = false)
	public void setValidatorFactory(ValidatorFactory validatorFactory) {
		this.validator = validatorFactory.getValidator();
	}


	@Override
	public boolean supports(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CliOption.class);
	}

	@Override
	public ValueResult resolve(MethodParameter methodParameter, List<String> words) {
		Optional<Converter<?>> converter = findOptionalConverter(methodParameter);
		CliOption cliOption = methodParameter.getParameterAnnotation(CliOption.class);

		Map<String, ParseResult> values = parseOptions(words);
		Map<String, ValueResult> seenValues = convertValues(values, methodParameter, converter);
		switch (seenValues.size()) {
		case 0:
			if (!cliOption.mandatory()) {
				String value = cliOption.unspecifiedDefaultValue();
				Object resolvedValue = converter
						.orElseThrow(noConverterFound(cliOption.key()[0], value, methodParameter.getParameterType()))
						.convertFromText(value, methodParameter.getParameterType(), cliOption.optionContext());

				return new ValueResult(methodParameter, resolvedValue);
			}
			else {
				throw new IllegalArgumentException("Could not find parameter values for "
						+ prettifyKeys(Arrays.asList(cliOption.key())) + " in " + words);
			}
		case 1:
			return seenValues.values().iterator().next();
		default:
			throw new RuntimeException("Option has been set multiple times via " + prettifyKeys(seenValues.keySet()));
		}
	}

	/**
	 * Maybe find a Shell 1 Converter that applies to the given {@literal methodParameter}.
	 */
	private Optional<Converter<?>> findOptionalConverter(MethodParameter methodParameter) {
		CliOption cliOption = methodParameter.getParameterAnnotation(CliOption.class);
		return converters.stream()
				.filter(c -> c.supports(methodParameter.getParameterType(), cliOption.optionContext()))
				.findFirst();
	}

	@Override
	public Stream<ParameterDescription> describe(MethodParameter parameter) {
		Parameter jlrParameter = parameter.getMethod().getParameters()[parameter.getParameterIndex()];
		CliOption option = jlrParameter.getAnnotation(CliOption.class);
		ParameterDescription result = ParameterDescription.outOf(parameter);
		result.help(option.help());
		List<String> keys = Arrays.asList(option.key());
		result.keys(notDefaultCommandKeys(parameter));
		if (!option.mandatory()) {
			result.defaultValue(CLI_OPTION_NULL.equals(option.unspecifiedDefaultValue()) ? "null"
					: option.unspecifiedDefaultValue());
		}
		if (!CLI_OPTION_NULL.equals(option.specifiedDefaultValue())) {
			result.whenFlag(option.specifiedDefaultValue());
		}
		boolean containsEmptyKey = keys.contains("");
		result.mandatoryKey(!containsEmptyKey);

		MethodDescriptor constraintsForMethod = validator.getConstraintsForClass(parameter.getDeclaringClass())
				.getConstraintsForMethod(parameter.getMethod().getName(), parameter.getMethod().getParameterTypes());
		if (constraintsForMethod != null) {
			ParameterDescriptor constraintsDescriptor = constraintsForMethod
					.getParameterDescriptors().get(parameter.getParameterIndex());
			result.elementDescriptor(constraintsDescriptor);
		}

		return Stream.of(result);
	}

	/**
	 * Return the list of keys (with their "--" prefix) that can be used to set the given
	 * parameter. If the parameter supports the empty key, this is not part of the result.
	 */
	private List<String> notDefaultCommandKeys(MethodParameter parameter) {
		Parameter jlrParameter = parameter.getMethod().getParameters()[parameter.getParameterIndex()];
		CliOption option = jlrParameter.getAnnotation(CliOption.class);
		return Arrays.stream(option.key())
				.filter(key -> !key.isEmpty())
				.map(key -> CLI_PREFIX + key)
				.collect(Collectors.toList());
	}

	@Override
	public List<CompletionProposal> complete(MethodParameter parameter, CompletionContext context) {
		String nextToLast = null;
		String last;
		if (context.getWords().size() >= 2) {
			nextToLast = context.getWords().get(context.getWords().size() - 2);
		}
		if (context.getWords().size() >= 1) {
			last = context.getWords().get(context.getWords().size() - 1);
		}
		else {
			last = null;
		}
		List<String> commandKeys = notDefaultCommandKeys(parameter);
		if (nextToLast != null) {
			if (commandKeys.contains(nextToLast)) {
				// nextToLast is our key, last is our (possibly unfinished) value
				if (findOptionalConverter(parameter).isPresent()) {
					ArrayList<Completion> legacyProposals = new ArrayList<>();
					findOptionalConverter(parameter).get().getAllPossibleValues(
							legacyProposals,
							parameter.getParameterType(),
							last,
							parameter.getParameterAnnotation(CliOption.class).optionContext(),
							craftMethodTarget()
					);
					return legacyProposals.stream()
							.filter(lp -> lp.getValue().startsWith(last))
							.map(this::toCompletionProposal)
							.collect(Collectors.toList());
				} else {
					return Collections.emptyList();
				}
			} // nextToLast looks like a key, but not for this parameter
			else if (nextToLast.startsWith(CLI_PREFIX)) {
				// Not for this parameter
				return Collections.emptyList();
			}
		}
		// Fallthrough: nextToLast is the value to another parameter
		// and last (possibly the empty string) could be our key
		if (last != null) {
			return commandKeys.stream()
					.filter(k -> k.startsWith(last))
					.map(CompletionProposal::new)
					.collect(Collectors.toList());
		}
		// Invoked completion just after the command (without a space): my-command<TAB>
		return Collections.emptyList();
	}

	/**
	 * Turn a Shell 1 Completion into a CompletionProposal.
	 */
	private CompletionProposal toCompletionProposal(Completion c) {
		return new CompletionProposal(c.getValue())
				.displayText(c.getFormattedValue())
				.category(c.getHeading());
	}

	// TODO pass invokable method in the completion context. Rarely used by converters, so ok for now
	private MethodTarget craftMethodTarget() {
		return new MethodTarget(ReflectionUtils.findMethod(Object.class, "toString"), "foo");
	}

	private Map<String, ParseResult> parseOptions(List<String> words) {
		Map<String, ParseResult> values = new HashMap<>();
		for (int i = 0; i < words.size(); i++) {
			int from = i;
			String word = words.get(i);
			if (word.startsWith(CLI_PREFIX)) {
				String key = word.substring(CLI_PREFIX.length());
				// If next word doesn't exist or starts with '--', this is an unary option. Store null
				String value = i < words.size() - 1 && !words.get(i + 1).startsWith(CLI_PREFIX) ? words.get(++i) : null;
				Assert.isTrue(!values.containsKey(key),
						String.format("Option %s%s has already been set", CLI_PREFIX, key));
				values.put(key, new ParseResult(value, from));
			} // Must be the 'anonymous' option
			else {
				Assert.isTrue(!values.containsKey(""), "Anonymous option has already been set");
				values.put("", new ParseResult(word, from));
			}
		}
		return values;
	}

	private Map<String, ValueResult> convertValues(Map<String, ParseResult> values, MethodParameter methodParameter,
			Optional<Converter<?>> converter) {
		Map<String, ValueResult> seenValues = new HashMap<>();
		CliOption option = methodParameter.getParameterAnnotation(CliOption.class);
		for (String key : option.key()) {
			if (values.containsKey(key)) {
				ParseResult parseResult = values.get(key);
				String value = parseResult.value;
				if (value == null && !CLI_OPTION_NULL.equals(option.specifiedDefaultValue())) {
					value = option.specifiedDefaultValue();
				}
				Class<?> parameterType = methodParameter.getParameterType();
				Object resolvedValue = converter
						.orElseThrow(noConverterFound(key, value, parameterType))
						.convertFromText(value, parameterType, option.optionContext());
				int from = parseResult.from;
				int to = key.isEmpty() || parseResult.value == null ? from : from + 1;
				BitSet wordsUsed = new BitSet();
				wordsUsed.set(from, to + 1);
				BitSet wordsUsedForValues = new BitSet();
				if (parseResult.value != null) {
					wordsUsedForValues.set(to);
				}
				seenValues.put(key, new ValueResult(methodParameter, resolvedValue, wordsUsed, wordsUsedForValues));
			}
		}
		return seenValues;
	}

	/**
	 * Return the list of possible keys for an option, suitable for displaying in an error
	 * message.
	 */
	private String prettifyKeys(Collection<String> keys) {
		return keys.stream().map(s -> "".equals(s) ? "<anonymous>" : CLI_PREFIX + s)
				.collect(Collectors.joining(", ", "[", "]"));
	}

	private Supplier<IllegalStateException> noConverterFound(String key, String value, Class<?> parameterType) {
		return () -> new IllegalStateException(
				"No converter found for " + CLI_PREFIX + key + " from '" + value + "' to type " + parameterType);
	}

	private static class ParseResult {
		private final String value;

		private final Integer from;

		public ParseResult(String value, Integer from) {
			this.value = value;
			this.from = from;
		}

	}

}
