// Copyright (C) 2014 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.config;

import com.google.gerrit.server.securestore.SecureStore;
import org.eclipse.jgit.lib.Config;

class GerritConfig extends Config {
  private final Config gerritConfig;
  private final SecureStore secureStore;

  GerritConfig(Config noteDbConfigOverGerritConfig, Config gerritConfig, SecureStore secureStore) {
    super(noteDbConfigOverGerritConfig);
    this.gerritConfig = gerritConfig;
    this.secureStore = secureStore;
  }

  @Override
  public String getString(String section, String subsection, String name) {
    String secure = secureStore.get(section, subsection, name);
    if (secure != null) {
      return secure;
    }
    return super.getString(section, subsection, name);
  }

  @Override
  public String[] getStringList(String section, String subsection, String name) {
    String[] secure = secureStore.getList(section, subsection, name);
    if (secure != null && secure.length > 0) {
      return secure;
    }
    return super.getStringList(section, subsection, name);
  }

  @Override
  public String toText() {
    // Only show the contents of gerrit.config, hiding the implementation detail that some values
    // may come from secure.config (or another secure store) and notedb.config.
    return gerritConfig.toText();
  }
}
