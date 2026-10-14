package com.github.j5ik2o.event.store.adapter.java.apisurface;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** java.json の java 側の名前（パッケージ内の名前。入れ子は {@code $}）からクラスを探す。 */
interface ClassLookup {

  Optional<Class<?>> find(String javaName);

  /** 指定パッケージの中から探す。 */
  static ClassLookup inPackage(String packageName) {
    return javaName -> load(packageName + "." + javaName);
  }

  /** 指定した名前だけ完全修飾名を差し替え、残りはこの探し方に任せる。 */
  default ClassLookup withOverrides(Map<String, String> fullyQualifiedNameByJavaName) {
    Map<String, String> overrides = new HashMap<>(fullyQualifiedNameByJavaName);
    return javaName -> {
      String replaced = overrides.get(javaName);
      return replaced == null ? find(javaName) : load(replaced);
    };
  }

  static Optional<Class<?>> load(String fullyQualifiedName) {
    try {
      return Optional.of(
          Class.forName(fullyQualifiedName, false, ClassLookup.class.getClassLoader()));
    } catch (ClassNotFoundException e) {
      return Optional.empty();
    }
  }
}
