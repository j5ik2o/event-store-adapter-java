package com.github.j5ik2o.event.store.adapter.java.core;

import javax.annotation.Nonnull;

/** Receives retention failures. / 保持処理の失敗を受け取る。 */
public interface RetentionFailureListener {

  /**
   * Called when the retention work fails. / 保持処理が失敗したときに呼ばれます。
   *
   * @param failure failure / 失敗
   */
  void onRetentionFailure(@Nonnull RetentionFailure failure);
}
