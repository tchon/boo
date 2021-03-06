/*
 * Copyright 2017 Walmart, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.oneops.boo;

import org.ini4j.Wini;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public class ClientConfigIniReader {

  public Map<String, String> read(File booConfigFile, String profile) throws IOException {
    Wini ini = new Wini(booConfigFile);
    return ini.get(profile);
  }
}
