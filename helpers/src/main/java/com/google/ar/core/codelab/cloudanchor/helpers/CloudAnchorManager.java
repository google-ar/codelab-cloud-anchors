/*
 * Copyright 2019 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.codelab.cloudanchor.helpers;

import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.Session;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * A helper class to handle all the Cloud Anchors logic, and add a callback-like mechanism on top of
 * the existing ARCore API.
 */
public class CloudAnchorManager {
  /** Listener for the results of a host or resolve operation. */
  public interface CloudAnchorListener {

    /** This method is invoked when the results of a Cloud Anchor operation are available. */
    void onCloudTaskComplete(Anchor anchor);
  }

  private final HashMap<Anchor, CloudAnchorListener> pendingAnchors = new HashMap<>();

  /**
   * This method hosts an anchor. The {@code listener} will be invoked when the results are
   * available.
   */
  public synchronized void hostCloudAnchor(
      Session session, Anchor anchor, int ttl, CloudAnchorListener listener) {
    Anchor newAnchor = session.hostCloudAnchorWithTtl(anchor, ttl);
    pendingAnchors.put(newAnchor, listener);
  }

  /**
   * This method resolves an anchor. The {@code listener} will be invoked when the results are
   * available.
   */
  public synchronized void resolveCloudAnchor(
      Session session, String anchorId, CloudAnchorListener listener) {
    Anchor newAnchor = session.resolveCloudAnchor(anchorId);
    pendingAnchors.put(newAnchor, listener);
  }

  /** Should be called after a {@link Session#update()} call. */
  public synchronized void onUpdate() {
    Iterator<Map.Entry<Anchor, CloudAnchorListener>> iter = pendingAnchors.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<Anchor, CloudAnchorListener> entry = iter.next();
      Anchor anchor = entry.getKey();
      if (isReturnableState(anchor.getCloudAnchorState())) {
        CloudAnchorListener listener = entry.getValue();
        listener.onCloudTaskComplete(anchor);
        iter.remove();
      }
    }
  }

  /** Used to clear any currently registered listeners, so they wont be called again. */
  public synchronized void clearListeners() {
    pendingAnchors.clear();
  }

  private static boolean isReturnableState(CloudAnchorState cloudState) {
    switch (cloudState) {
      case NONE:
      case TASK_IN_PROGRESS:
        return false;
      default:
        return true;
    }
  }
}
