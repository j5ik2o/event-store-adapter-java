package com.github.j5ik2o.event.store.adapter.java.apisurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * surface.json（共通の形）と java.json（java の対応）に従い、コンパイル済みの公開 API の形を照合する。
 *
 * <p>ずれは「surface 側の経路: 説明」の形のメッセージで返す。対応の抜けとクラスを解決できない場合も、黙って飛ばさず報告する。
 */
final class ApiSurfaceChecker {

  private final JsonNode surface;
  private final JsonNode mapping;
  private final ClassLookup lookup;
  private final List<String> problems = new ArrayList<>();

  private ApiSurfaceChecker(JsonNode surface, JsonNode mapping, ClassLookup lookup) {
    this.surface = surface;
    this.mapping = mapping;
    this.lookup = lookup;
  }

  /** ずれのメッセージの一覧を返す。形が合っていれば空。 */
  static List<String> check(JsonNode surface, JsonNode mapping, ClassLookup lookup) {
    ApiSurfaceChecker checker = new ApiSurfaceChecker(surface, mapping, lookup);
    for (JsonNode type : checker.surface.path("types")) {
      checker.checkType(type);
    }
    checker.checkStores();
    checker.checkErrorCategories();
    return checker.problems;
  }

  // ---- 型と要素 ----

  private void checkType(JsonNode type) {
    String name = type.path("name").asText();
    JsonNode typeMapping = mapping.path("types").path(name);
    if (typeMapping.isMissingNode()) {
      problems.add(name + ": no mapping in java.json (types)");
      return;
    }
    String className = typeMapping.path("class").asText();
    Optional<Class<?>> found = lookup.find(className);
    if (!found.isPresent()) {
      problems.add(name + ": class " + className + " not found");
      return;
    }
    Class<?> cls = found.get();
    checkTypeParameters(name, cls, type.path("type_parameters"));
    for (JsonNode element : type.path("elements")) {
      checkAccessor(name, cls, typeMapping, element);
    }
    if (hasRequiredFlag(type)) {
      checkBuilder(name, cls, typeMapping, type);
    }
  }

  private void checkTypeParameters(String path, Class<?> cls, JsonNode surfaceParameters) {
    List<String> want = new ArrayList<>();
    for (JsonNode parameter : surfaceParameters) {
      JsonNode mapped = mapping.path("type_parameters").path(parameter.asText());
      if (mapped.isMissingNode()) {
        problems.add(
            path + ": no mapping in java.json (type_parameters) for " + parameter.asText());
        return;
      }
      want.add(mapped.asText());
    }
    TypeVariable<?>[] actual = cls.getTypeParameters();
    List<String> actualNames = new ArrayList<>();
    for (TypeVariable<?> variable : actual) {
      actualNames.add(variable.getName());
    }
    if (!actualNames.equals(want)) {
      problems.add(
          path
              + ": "
              + cls.getSimpleName()
              + " declares type parameters "
              + actualNames
              + ", want "
              + want);
      return;
    }
    for (TypeVariable<?> variable : actual) {
      Type[] bounds = variable.getBounds();
      if (bounds.length != 1 || bounds[0] != Object.class) {
        problems.add(
            path
                + ": type parameter "
                + variable.getName()
                + " of "
                + cls.getSimpleName()
                + " is bounded by "
                + Arrays.toString(bounds)
                + "; want no bound");
      }
    }
  }

  private void checkAccessor(
      String typePath, Class<?> cls, JsonNode typeMapping, JsonNode element) {
    String path = typePath + "." + element.path("name").asText();
    JsonNode accessor = typeMapping.path("accessors").path(element.path("name").asText());
    if (accessor.isMissingNode()) {
      problems.add(path + ": no mapping in java.json (accessors)");
      return;
    }
    Expr want = Expr.parse(element.path("type").asText());
    if (!isMapped(path, want)) {
      return;
    }
    Method method = findPublicMethod(cls, accessor.asText(), 0);
    if (method == null) {
      problems.add(
          path + ": " + cls.getSimpleName() + " has no accessor " + accessor.asText() + "()");
      return;
    }
    Type actual = method.getGenericReturnType();
    if (!matches(want, actual)) {
      problems.add(
          path
              + ": "
              + cls.getSimpleName()
              + "."
              + accessor.asText()
              + "() returns "
              + actual.getTypeName()
              + ", want "
              + describe(want));
    }
  }

  // ---- ビルダー（型には現れない「必須」と「省略」を実際に呼んで確かめる） ----

  private void checkBuilder(String path, Class<?> cls, JsonNode typeMapping, JsonNode type) {
    JsonNode builder = typeMapping.path("builder");
    if (builder.isMissingNode()) {
      problems.add(path + ": no mapping in java.json (builder)");
      return;
    }
    Optional<Class<?>> builderClass = lookup.find(builder.path("class").asText());
    if (!builderClass.isPresent()) {
      problems.add(path + ": builder class " + builder.path("class").asText() + " not found");
      return;
    }
    Method factory = findPublicMethod(cls, builder.path("factory").asText(), 0);
    if (factory == null || !Modifier.isStatic(factory.getModifiers())) {
      problems.add(
          path
              + ": "
              + cls.getSimpleName()
              + " has no static "
              + builder.path("factory").asText()
              + "()");
      return;
    }
    if (rawClass(factory.getGenericReturnType()) != builderClass.get()) {
      problems.add(
          path
              + ": "
              + cls.getSimpleName()
              + "."
              + factory.getName()
              + "() returns "
              + factory.getGenericReturnType().getTypeName()
              + ", want "
              + builderClass.get().getSimpleName());
      return;
    }
    Method build = findPublicMethod(builderClass.get(), builder.path("build").asText(), 0);
    if (build == null) {
      problems.add(
          path
              + ": "
              + builderClass.get().getSimpleName()
              + " has no "
              + builder.path("build").asText()
              + "()");
      return;
    }
    List<JsonNode> elements = new ArrayList<>();
    List<Method> setters = new ArrayList<>();
    boolean complete = true;
    for (JsonNode element : type.path("elements")) {
      String elementPath = path + "." + element.path("name").asText();
      JsonNode setterName = builder.path("setters").path(element.path("name").asText());
      if (setterName.isMissingNode()) {
        problems.add(elementPath + ": no mapping in java.json (builder.setters)");
        complete = false;
        continue;
      }
      Method setter = findPublicMethod(builderClass.get(), setterName.asText(), 1);
      if (setter == null) {
        problems.add(
            elementPath
                + ": "
                + builderClass.get().getSimpleName()
                + " has no setter "
                + setterName.asText()
                + "(value)");
        complete = false;
        continue;
      }
      elements.add(element);
      setters.add(setter);
    }
    if (!complete) {
      return;
    }
    Optional<Class<?>> expected =
        lookup.find(
            mapping
                .path("error_categories")
                .path("names")
                .path(builder.path("missing_required").asText())
                .path("class")
                .asText());
    if (!expected.isPresent()) {
      return; // 分類のクラスが無いことは checkErrorCategories が報告する
    }
    Outcome all = invokeBuilder(factory, build, elements, setters, -1);
    if (all.error != null) {
      problems.add(
          path + ": build() with every element set threw " + all.error.getClass().getName());
      return;
    }
    for (int i = 0; i < elements.size(); i++) {
      JsonNode element = elements.get(i);
      String elementPath = path + "." + element.path("name").asText();
      Outcome without = invokeBuilder(factory, build, elements, setters, i);
      if (element.path("required").asBoolean(false)) {
        String want = expected.get().getSimpleName();
        if (without.error == null) {
          problems.add(
              elementPath
                  + ": build() without "
                  + element.path("name").asText()
                  + " succeeded; want "
                  + want);
        } else if (without.error.getClass() != expected.get()) {
          problems.add(
              elementPath
                  + ": build() without "
                  + element.path("name").asText()
                  + " threw "
                  + without.error.getClass().getName()
                  + "; want "
                  + want);
        }
      } else {
        checkOptionalElement(path, cls, typeMapping, element, elementPath, without);
      }
    }
  }

  private void checkOptionalElement(
      String path,
      Class<?> cls,
      JsonNode typeMapping,
      JsonNode element,
      String elementPath,
      Outcome without) {
    if (without.error != null) {
      problems.add(
          elementPath
              + ": build() without optional "
              + element.path("name").asText()
              + " threw "
              + without.error.getClass().getName());
      return;
    }
    Method accessor =
        findPublicMethod(
            cls, typeMapping.path("accessors").path(element.path("name").asText()).asText(), 0);
    if (accessor == null) {
      return; // 取得のメソッドが無いことは checkAccessor が報告済み
    }
    try {
      Object value = accessor.invoke(without.value);
      String want = element.path("default").asText();
      if (!String.valueOf(value).equals(want)) {
        problems.add(
            elementPath + ": default is " + quote(String.valueOf(value)) + ", want " + quote(want));
      }
    } catch (ReflectiveOperationException e) {
      problems.add(elementPath + ": reading the default failed: " + e);
    }
  }

  private Outcome invokeBuilder(
      Method factory, Method build, List<JsonNode> elements, List<Method> setters, int skipped) {
    try {
      Object builder = factory.invoke(null);
      for (int i = 0; i < elements.size(); i++) {
        if (i != skipped) {
          setters.get(i).invoke(builder, sample(elements.get(i).path("type").asText()));
        }
      }
      return new Outcome(build.invoke(builder), null);
    } catch (InvocationTargetException e) {
      return new Outcome(null, e.getCause());
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      // 設定のメソッドの引数の型がずれていると、Method.invoke は IllegalArgumentException を投げる。
      // 照合を止めずに、ずれとして報告する。
      return new Outcome(null, e);
    }
  }

  /** 要素の種類から見本の値を作る。型の引数（P・A）は任意の値でよい。 */
  private Object sample(String type) {
    switch (type) {
      case "aggregate_id":
        return AggregateId.of("t", "v");
      case "seq_nr":
        return 1L;
      case "timestamp":
        return Instant.EPOCH;
      case "string":
        return "m";
      default:
        if (mapping.path("type_parameters").has(type)) {
          return "sample";
        }
        throw new IllegalStateException("no sample value for builder element type: " + type);
    }
  }

  // ---- ストア ----

  private void checkStores() {
    JsonNode store = surface.path("store");
    if (!mapping.path("stores").isArray() || mapping.path("stores").size() == 0) {
      problems.add(store.path("name").asText() + ": no mapping in java.json (stores)");
      return;
    }
    for (JsonNode storeMapping : mapping.path("stores")) {
      checkStore(store, storeMapping);
    }
  }

  private void checkStore(JsonNode store, JsonNode storeMapping) {
    String interfaceName = storeMapping.path("interface").asText();
    Optional<Class<?>> found = lookup.find(interfaceName);
    if (!found.isPresent()) {
      problems.add(store.path("name").asText() + ": interface " + interfaceName + " not found");
      return;
    }
    Class<?> cls = found.get();
    checkTypeParameters(store.path("name").asText(), cls, store.path("type_parameters"));
    for (JsonNode operation : store.path("operations")) {
      checkOperation(cls, storeMapping, operation);
    }
  }

  private void checkOperation(Class<?> cls, JsonNode storeMapping, JsonNode operation) {
    String path = operation.path("name").asText();
    JsonNode methodName = mapping.path("operations").path(path);
    if (methodName.isMissingNode()) {
      problems.add(path + ": no mapping in java.json (operations)");
      return;
    }
    List<Expr> inputs = new ArrayList<>();
    for (JsonNode input : operation.path("inputs")) {
      inputs.add(Expr.parse(input.path("type").asText()));
    }
    String output = operation.path("output").asText();
    Expr outputExpr = "none".equals(output) ? null : Expr.parse(output);
    for (Expr input : inputs) {
      if (!isMapped(path, input)) {
        return;
      }
    }
    if (outputExpr != null && !isMapped(path, outputExpr)) {
      return;
    }
    Method method = findPublicMethod(cls, methodName.asText(), inputs.size());
    if (method == null) {
      problems.add(
          path
              + ": "
              + cls.getSimpleName()
              + " has no method "
              + methodName.asText()
              + " taking "
              + inputs.size()
              + " parameter(s)");
      return;
    }
    Type[] parameters = method.getGenericParameterTypes();
    for (int i = 0; i < inputs.size(); i++) {
      if (!matches(inputs.get(i), parameters[i])) {
        problems.add(
            path
                + ": "
                + cls.getSimpleName()
                + "."
                + method.getName()
                + " parameter "
                + (i + 1)
                + " is "
                + parameters[i].getTypeName()
                + ", want "
                + describe(inputs.get(i)));
      }
    }
    String wrap = storeMapping.path("wrap").asText(null);
    Type returned = method.getGenericReturnType();
    if (!matchesOutput(outputExpr, wrap, storeMapping.path("none").asText(), returned)) {
      problems.add(
          path
              + ": "
              + cls.getSimpleName()
              + "."
              + method.getName()
              + " returns "
              + returned.getTypeName()
              + ", want "
              + describeOutput(outputExpr, wrap, storeMapping.path("none").asText()));
    }
  }

  private boolean matchesOutput(Expr output, String wrap, String none, Type actual) {
    if (wrap == null) {
      return output == null
          ? actual instanceof Class && ((Class<?>) actual).getName().equals(none)
          : matches(output, actual);
    }
    if (!(actual instanceof ParameterizedType)) {
      return false;
    }
    ParameterizedType parameterized = (ParameterizedType) actual;
    if (!parameterized.getRawType().getTypeName().equals(wrap)
        || parameterized.getActualTypeArguments().length != 1) {
      return false;
    }
    Type inner = parameterized.getActualTypeArguments()[0];
    return output == null
        ? inner instanceof Class && ((Class<?>) inner).getName().equals(none)
        : matches(output, inner);
  }

  private String describeOutput(Expr output, String wrap, String none) {
    String inner = output == null ? none : describe(output);
    return wrap == null ? inner : wrap + "<" + inner + ">";
  }

  // ---- エラーの分類 ----

  private void checkErrorCategories() {
    JsonNode categories = mapping.path("error_categories");
    Optional<Class<?>> base = lookup.find(categories.path("base").asText());
    Optional<Class<?>> constants = lookup.find(categories.path("enum").asText());
    if (!base.isPresent()) {
      problems.add(
          "error_categories: base class " + categories.path("base").asText() + " not found");
    }
    if (!constants.isPresent()) {
      problems.add("error_categories: enum " + categories.path("enum").asText() + " not found");
    }
    for (JsonNode nameNode : surface.path("error_categories").path("names")) {
      String name = nameNode.asText();
      JsonNode entry = categories.path("names").path(name);
      if (entry.isMissingNode()) {
        problems.add(name + ": no mapping in java.json (error_categories)");
        continue;
      }
      String className = entry.path("class").asText();
      Optional<Class<?>> found = lookup.find(className);
      if (!found.isPresent()) {
        problems.add(name + ": class " + className + " not found");
      } else {
        checkExceptionClass(name, found.get(), base, categories.path("unchecked").asBoolean(false));
      }
      if (constants.isPresent() && !hasConstant(constants.get(), entry.path("constant").asText())) {
        problems.add(
            name
                + ": "
                + constants.get().getSimpleName()
                + " has no constant "
                + entry.path("constant").asText());
      }
    }
  }

  private void checkExceptionClass(
      String name, Class<?> cls, Optional<Class<?>> base, boolean unchecked) {
    if (base.isPresent() && (cls == base.get() || !base.get().isAssignableFrom(cls))) {
      problems.add(
          name
              + ": "
              + cls.getSimpleName()
              + " is not a subclass of "
              + base.get().getSimpleName());
    }
    if (unchecked && !RuntimeException.class.isAssignableFrom(cls)) {
      problems.add(name + ": " + cls.getSimpleName() + " is a checked exception; want unchecked");
    }
  }

  private static boolean hasConstant(Class<?> enumClass, String constant) {
    Object[] constants = enumClass.getEnumConstants();
    if (constants == null) {
      return false;
    }
    for (Object candidate : constants) {
      if (((Enum<?>) candidate).name().equals(constant)) {
        return true;
      }
    }
    return false;
  }

  // ---- 型の式の照合 ----

  /** surface.json の型の式（{@code optional<snapshot_envelope<A>>} など）。 */
  private static final class Expr {
    final String name;
    final List<Expr> arguments;

    Expr(String name, List<Expr> arguments) {
      this.name = name;
      this.arguments = arguments;
    }

    static Expr parse(String text) {
      int[] position = {0};
      Expr expr = parse(text, position);
      if (position[0] != text.length()) {
        throw new IllegalArgumentException("unexpected text in type expression: " + text);
      }
      return expr;
    }

    private static Expr parse(String text, int[] position) {
      int start = position[0];
      while (position[0] < text.length()
          && (Character.isLetterOrDigit(text.charAt(position[0]))
              || text.charAt(position[0]) == '_')) {
        position[0]++;
      }
      String name = text.substring(start, position[0]);
      List<Expr> arguments = new ArrayList<>();
      if (position[0] < text.length() && text.charAt(position[0]) == '<') {
        position[0]++;
        arguments.add(parse(text, position));
        while (position[0] < text.length() && text.charAt(position[0]) == ',') {
          position[0]++;
          arguments.add(parse(text, position));
        }
        if (position[0] >= text.length() || text.charAt(position[0]) != '>') {
          throw new IllegalArgumentException("missing '>' in type expression: " + text);
        }
        position[0]++;
      }
      return new Expr(name, arguments);
    }
  }

  /** 式に出てくる名前がすべて java.json に対応していることを確かめる。無ければ報告する。 */
  private boolean isMapped(String path, Expr expr) {
    boolean known =
        mapping.path("type_parameters").has(expr.name)
            || mapping.path("value_types").has(expr.name)
            || mapping.path("containers").has(expr.name)
            || mapping.path("types").has(expr.name);
    if (!known) {
      problems.add(path + ": no mapping in java.json for '" + expr.name + "'");
      return false;
    }
    for (Expr argument : expr.arguments) {
      if (!isMapped(path, argument)) {
        return false;
      }
    }
    return true;
  }

  private boolean matches(Expr expr, Type actual) {
    JsonNode parameter = mapping.path("type_parameters").path(expr.name);
    if (!parameter.isMissingNode()) {
      return expr.arguments.isEmpty()
          && actual instanceof TypeVariable
          && ((TypeVariable<?>) actual).getName().equals(parameter.asText());
    }
    JsonNode value = mapping.path("value_types").path(expr.name);
    if (!value.isMissingNode()) {
      return expr.arguments.isEmpty()
          && actual instanceof Class
          && ((Class<?>) actual).getName().equals(value.asText());
    }
    JsonNode container = mapping.path("containers").path(expr.name);
    if (!container.isMissingNode()) {
      return actual instanceof ParameterizedType
          && ((ParameterizedType) actual).getRawType().getTypeName().equals(container.asText())
          && argumentsMatch(expr, (ParameterizedType) actual);
    }
    // surface の型は、単純名と型の引数で比べる（試験の側でずらしたクラスと本物を混ぜて照合するため）
    String simpleName = mapping.path("types").path(expr.name).path("class").asText();
    Class<?> raw = rawClass(actual);
    if (raw == null || !raw.getSimpleName().equals(simpleName)) {
      return false;
    }
    return actual instanceof ParameterizedType
        ? argumentsMatch(expr, (ParameterizedType) actual)
        : expr.arguments.isEmpty();
  }

  private boolean argumentsMatch(Expr expr, ParameterizedType actual) {
    Type[] actualArguments = actual.getActualTypeArguments();
    if (actualArguments.length != expr.arguments.size()) {
      return false;
    }
    for (int i = 0; i < actualArguments.length; i++) {
      if (!matches(expr.arguments.get(i), actualArguments[i])) {
        return false;
      }
    }
    return true;
  }

  private String describe(Expr expr) {
    String base;
    JsonNode parameter = mapping.path("type_parameters").path(expr.name);
    if (!parameter.isMissingNode()) {
      base = parameter.asText();
    } else if (mapping.path("value_types").has(expr.name)) {
      base = mapping.path("value_types").path(expr.name).asText();
    } else if (mapping.path("containers").has(expr.name)) {
      base = mapping.path("containers").path(expr.name).asText();
    } else {
      base = mapping.path("types").path(expr.name).path("class").asText();
    }
    if (expr.arguments.isEmpty()) {
      return base;
    }
    StringBuilder text = new StringBuilder(base).append('<');
    for (Iterator<Expr> it = expr.arguments.iterator(); it.hasNext(); ) {
      text.append(describe(it.next()));
      if (it.hasNext()) {
        text.append(", ");
      }
    }
    return text.append('>').toString();
  }

  // ---- 補助 ----

  private static Method findPublicMethod(Class<?> cls, String name, int parameterCount) {
    for (Method method : cls.getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
        return method;
      }
    }
    return null;
  }

  private static Class<?> rawClass(Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    }
    if (type instanceof ParameterizedType
        && ((ParameterizedType) type).getRawType() instanceof Class) {
      return (Class<?>) ((ParameterizedType) type).getRawType();
    }
    return null;
  }

  private static boolean hasRequiredFlag(JsonNode type) {
    for (JsonNode element : type.path("elements")) {
      if (element.has("required")) {
        return true;
      }
    }
    return false;
  }

  private static String quote(String text) {
    return "\"" + text + "\"";
  }

  private static final class Outcome {
    final Object value;
    final Throwable error;

    Outcome(Object value, Throwable error) {
      this.value = value;
      this.error = error;
    }
  }
}
