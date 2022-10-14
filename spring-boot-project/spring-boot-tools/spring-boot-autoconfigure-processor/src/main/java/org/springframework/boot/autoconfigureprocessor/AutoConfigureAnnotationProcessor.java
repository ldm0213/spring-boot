/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.boot.autoconfigureprocessor;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

/**
 * Annotation processor to store certain annotations from auto-configuration classes in a
 * property file.
 *
 * Processor会在编译阶段初始化，然后对当前模块内的代码进行一次扫描，然后获取到对应的注解，之后调用process方法，然后我们根据这些注解类来做一些后续操作
 *
 * Spring Boot提供的自动配置类比较多，而我们不可能使用到很多自动配置功能，大部分都没必要，如果每次你启动应用的过程中，
 * 都需要一个一个去解析他们上面的 Conditional 注解，那么肯定会有不少的性能损耗，Spring Boot 通过AutoConfigureAnnotationProcessor，
 * 在编译阶段将自动配置类的一些注解信息保存在一个 properties 文件中，这样一来，启动应用的过程中，
 * 就可以直接读取该文件中的信息，提前过滤掉一些自动配置类，相比于每次都去解析它们所有的注解，性能提升不少
 *
 *
 * spring-boot-autoconfigure 模块会引入该工具模块，那么 Spring Boot 在编译 spring-boot-autoconfigure 这个 jar 包的时候，
 * 在编译阶段会扫描到带有 @ConditionalOnClass 等注解的 .class 文件，也就是自动配置类，
 * 然后将自动配置类的一些信息保存至 META-INF/spring-autoconfigure-metadata.properties 文件中
 *
 * @author Madhura Bhave
 * @author Phillip Webb
 * @since 1.5.0
 */
@SupportedAnnotationTypes({ "org.springframework.boot.autoconfigure.condition.ConditionalOnClass",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate",
		"org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication",
		"org.springframework.boot.autoconfigure.AutoConfigureBefore",
		"org.springframework.boot.autoconfigure.AutoConfigureAfter",
		"org.springframework.boot.autoconfigure.AutoConfigureOrder" })
public class AutoConfigureAnnotationProcessor extends AbstractProcessor {

	protected static final String PROPERTIES_PATH = "META-INF/spring-autoconfigure-metadata.properties";

	private final Map<String, String> annotations;

	private final Map<String, ValueExtractor> valueExtractors;

	private final Map<String, String> properties = new TreeMap<>();

	public AutoConfigureAnnotationProcessor() {
		Map<String, String> annotations = new LinkedHashMap<>();
		addAnnotations(annotations);
		this.annotations = Collections.unmodifiableMap(annotations);
		Map<String, ValueExtractor> valueExtractors = new LinkedHashMap<>();
		addValueExtractors(valueExtractors);
		this.valueExtractors = Collections.unmodifiableMap(valueExtractors);
	}

	protected void addAnnotations(Map<String, String> annotations) {
		annotations.put("ConditionalOnClass", "org.springframework.boot.autoconfigure.condition.ConditionalOnClass");
		annotations.put("ConditionalOnBean", "org.springframework.boot.autoconfigure.condition.ConditionalOnBean");
		annotations.put("ConditionalOnSingleCandidate",
				"org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate");
		annotations.put("ConditionalOnWebApplication",
				"org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication");
		annotations.put("AutoConfigureBefore", "org.springframework.boot.autoconfigure.AutoConfigureBefore");
		annotations.put("AutoConfigureAfter", "org.springframework.boot.autoconfigure.AutoConfigureAfter");
		annotations.put("AutoConfigureOrder", "org.springframework.boot.autoconfigure.AutoConfigureOrder");
	}

	private void addValueExtractors(Map<String, ValueExtractor> attributes) {
		attributes.put("ConditionalOnClass", new OnClassConditionValueExtractor());
		attributes.put("ConditionalOnBean", new OnBeanConditionValueExtractor());
		attributes.put("ConditionalOnSingleCandidate", new OnBeanConditionValueExtractor());
		attributes.put("ConditionalOnWebApplication", ValueExtractor.allFrom("type"));
		attributes.put("AutoConfigureBefore", ValueExtractor.allFrom("value", "name"));
		attributes.put("AutoConfigureAfter", ValueExtractor.allFrom("value", "name"));
		attributes.put("AutoConfigureOrder", ValueExtractor.allFrom("value"));
	}

	@Override
	public SourceVersion getSupportedSourceVersion() {
		return SourceVersion.latestSupported();
	}

	@Override
	public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
		// 遍历上面的几个 `@Conditional` 注解和几个定义自动配置类顺序的注解，依次进行处理
		for (Map.Entry<String, String> entry : this.annotations.entrySet()) {
			// 找到标注了指定注解的类，然后解析出该注解的值，保存至 Properties
			// 例如 `类名.注解简称` => `注解中的值(逗号分隔)` 和 `类名` => `空字符串`，将自动配置类的信息已经对应注解的信息都保存起来
			process(roundEnv, entry.getKey(), entry.getValue());
		}
		if (roundEnv.processingOver()) {
			try {
				// 将 Properties 写入 `META-INF/spring-autoconfigure-metadata.properties` 文件
				writeProperties();
			}
			catch (Exception ex) {
				throw new IllegalStateException("Failed to write metadata", ex);
			}
		}
		return false;
	}

	private void process(RoundEnvironment roundEnv, String propertyKey, String annotationName) {
		// 获取到这个注解名称对应的 Java 类型
		TypeElement annotationType = this.processingEnv.getElementUtils().getTypeElement(annotationName);
		if (annotationType != null) {
			// 如果存在该注解，则从 RoundEnvironment 中获取标注了该注解的所有 Element 元素，进行遍历
			for (Element element : roundEnv.getElementsAnnotatedWith(annotationType)) {
				processElement(element, propertyKey, annotationName);
			}
		}
	}

	private void processElement(Element element, String propertyKey, String annotationName) {
		try {
			// 获取这个类的名称
			String qualifiedName = Elements.getQualifiedName(element);
			// 获取这个类上面的 `annotationName` 类型的注解信息
			AnnotationMirror annotation = getAnnotation(element, annotationName);
			if (qualifiedName != null && annotation != null) {
				// 获取这个注解中的值
				List<Object> values = getValues(propertyKey, annotation);
				// 往 `properties` 中添加 `类名.注解简称` => `注解中的值(逗号分隔)`
				this.properties.put(qualifiedName + "." + propertyKey, toCommaDelimitedString(values));
				// 往 `properties` 中添加 `类名` => `空字符串`
				this.properties.put(qualifiedName, "");
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("Error processing configuration meta-data on " + element, ex);
		}
	}

	private AnnotationMirror getAnnotation(Element element, String type) {
		if (element != null) {
			for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
				if (type.equals(annotation.getAnnotationType().toString())) {
					return annotation;
				}
			}
		}
		return null;
	}

	private String toCommaDelimitedString(List<Object> list) {
		StringBuilder result = new StringBuilder();
		for (Object item : list) {
			result.append((result.length() != 0) ? "," : "");
			result.append(item);
		}
		return result.toString();
	}

	private List<Object> getValues(String propertyKey, AnnotationMirror annotation) {
		ValueExtractor extractor = this.valueExtractors.get(propertyKey);
		if (extractor == null) {
			return Collections.emptyList();
		}
		return extractor.getValues(annotation);
	}

	private void writeProperties() throws IOException {
		if (!this.properties.isEmpty()) {
			Filer filer = this.processingEnv.getFiler();
			FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", PROPERTIES_PATH);
			try (Writer writer = new OutputStreamWriter(file.openOutputStream(), StandardCharsets.UTF_8)) {
				for (Map.Entry<String, String> entry : this.properties.entrySet()) {
					// 自动配置类类名.注解简称 --> 注解中的值(逗号分隔)，自动配置类类名 --> 空字符串
					writer.append(entry.getKey());
					writer.append("=");
					writer.append(entry.getValue());
					writer.append(System.lineSeparator());
				}
			}
		}
	}

	@FunctionalInterface
	private interface ValueExtractor {

		List<Object> getValues(AnnotationMirror annotation);

		static ValueExtractor allFrom(String... names) {
			return new NamedValuesExtractor(names);
		}

	}

	private abstract static class AbstractValueExtractor implements ValueExtractor {

		@SuppressWarnings("unchecked")
		protected Stream<Object> extractValues(AnnotationValue annotationValue) {
			if (annotationValue == null) {
				return Stream.empty();
			}
			Object value = annotationValue.getValue();
			if (value instanceof List) {
				return ((List<AnnotationValue>) value).stream()
						.map((annotation) -> extractValue(annotation.getValue()));
			}
			return Stream.of(extractValue(value));
		}

		private Object extractValue(Object value) {
			if (value instanceof DeclaredType) {
				return Elements.getQualifiedName(((DeclaredType) value).asElement());
			}
			return value;
		}

	}

	private static class NamedValuesExtractor extends AbstractValueExtractor {

		private final Set<String> names;

		NamedValuesExtractor(String... names) {
			this.names = new HashSet<>(Arrays.asList(names));
		}

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			List<Object> result = new ArrayList<>();
			annotation.getElementValues().forEach((key, value) -> {
				if (this.names.contains(key.getSimpleName().toString())) {
					extractValues(value).forEach(result::add);
				}
			});
			return result;
		}

	}

	private static class OnBeanConditionValueExtractor extends AbstractValueExtractor {

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			Map<String, AnnotationValue> attributes = new LinkedHashMap<>();
			annotation.getElementValues()
					.forEach((key, value) -> attributes.put(key.getSimpleName().toString(), value));
			if (attributes.containsKey("name")) {
				return Collections.emptyList();
			}
			List<Object> result = new ArrayList<>();
			extractValues(attributes.get("value")).forEach(result::add);
			extractValues(attributes.get("type")).forEach(result::add);
			return result;
		}

	}

	private static class OnClassConditionValueExtractor extends NamedValuesExtractor {

		OnClassConditionValueExtractor() {
			super("value", "name");
		}

		@Override
		public List<Object> getValues(AnnotationMirror annotation) {
			List<Object> values = super.getValues(annotation);
			values.sort(this::compare);
			return values;
		}

		private int compare(Object o1, Object o2) {
			return Comparator.comparing(this::isSpringClass).thenComparing(String.CASE_INSENSITIVE_ORDER)
					.compare(o1.toString(), o2.toString());
		}

		private boolean isSpringClass(String type) {
			return type.startsWith("org.springframework");
		}

	}

}
