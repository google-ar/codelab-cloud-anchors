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

package com.google.ar.core.codelab.cloudanchor;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;

/**
 * Main Activity for the Cloud Anchors Codelab.
 *
 * <p>The bulk of the logic resides in the {@link CloudAnchorFragment}. The activity only creates
 * the fragment and attaches it to this Activity.
 */
public class MainActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle bundle) {
    super.onCreate(bundle);
    setContentView(R.layout.activity_main);

    FragmentManager fm = getSupportFragmentManager();
    Fragment frag = fm.findFragmentById(R.id.fragment_container);
    if (frag == null) {
      frag = new CloudAnchorFragment();
      fm.beginTransaction().add(R.id.fragment_container, frag).commit();
    }
  }
}
