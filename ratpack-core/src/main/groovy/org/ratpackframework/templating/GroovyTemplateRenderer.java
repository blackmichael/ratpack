/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ratpackframework.templating;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.codehaus.groovy.runtime.IOGroovyMethods;
import org.ratpackframework.config.LayoutConfig;
import org.ratpackframework.config.TemplatingConfig;
import org.ratpackframework.script.internal.ScriptEngine;
import org.ratpackframework.templating.internal.CompiledTemplate;
import org.ratpackframework.templating.internal.Render;
import org.ratpackframework.templating.internal.TemplateCompiler;
import org.ratpackframework.templating.internal.TemplateScript;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.buffer.Buffer;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.Map;

public class GroovyTemplateRenderer implements TemplateRenderer {

  private final String templateDirPath;
  private final Vertx vertx;
  private final String errorPageTemplate;

  private final Cache<String, CompiledTemplate> compiledTemplateCache;
  private final boolean staticallyCompile;

  @Inject
  public GroovyTemplateRenderer(Vertx vertx, LayoutConfig layoutConfig, TemplatingConfig templatingConfig) {
    this.vertx = vertx;
    this.staticallyCompile = templatingConfig.isStaticallyCompile();
    this.templateDirPath = new File(layoutConfig.getBaseDir(), templatingConfig.getDir()).getAbsolutePath();
    this.errorPageTemplate = getResourceText("exception.html");
    this.compiledTemplateCache = CacheBuilder.newBuilder().maximumSize(templatingConfig.getCacheSize()).build();
  }

  @Override
  public void renderFileTemplate(final String templateFileName, final Map<String, ?> model, final AsyncResultHandler<Buffer> handler) {
    final TemplateCompiler templateCompiler = createCompiler();
    CompiledTemplate cachedTemplate = compiledTemplateCache.getIfPresent(templateFileName);
    if (cachedTemplate != null) {
      render(templateCompiler, cachedTemplate, model, handler);
    } else {
      vertx.fileSystem().readFile(getTemplatePath(templateFileName), new AsyncResultHandler<Buffer>() {
        @Override
        public void handle(AsyncResult<Buffer> event) {
          if (event.failed()) {
            handler.handle(new AsyncResult<Buffer>(event.exception));
          } else {
            try {
              CompiledTemplate compiledTemplate = templateCompiler.compile(event.result, templateFileName);
              compiledTemplateCache.put(templateFileName, compiledTemplate);
              render(templateCompiler, compiledTemplate, model, handler);
            } catch (Exception e) {
              handler.handle(new AsyncResult<Buffer>(e));
            }
          }
        }
      });
    }
  }

  @Override
  public void renderError(Map<String, ?> model, AsyncResultHandler<Buffer> handler) {
    render(errorPageTemplate, "errorpage", model, handler);
  }

  private void render(String template, String templateName, Map<String, ?> model, AsyncResultHandler<Buffer> handler) {
    try {
      TemplateCompiler templateCompiler = createCompiler();
      CompiledTemplate compiledTemplate = templateCompiler.compile(new Buffer(template), templateName);
      render(templateCompiler, compiledTemplate, model, handler);
    } catch (Exception e) {
      handler.handle(new AsyncResult<Buffer>(e));
    }
  }

  private TemplateCompiler createCompiler() {
    return new TemplateCompiler(new ScriptEngine<TemplateScript>(getClass().getClassLoader(), staticallyCompile, TemplateScript.class));
  }

  private void render(TemplateCompiler templateCompiler, CompiledTemplate compiledTemplate, Map<String, ?> model, AsyncResultHandler<Buffer> handler) {
    new Render(templateCompiler, vertx.fileSystem(), compiledTemplateCache, templateDirPath, compiledTemplate, model, handler);
  }

  private String getTemplatePath(String templateName) {
    return templateDirPath + File.separator + templateName;
  }

  private String getResourceText(String resourceName) {
    try {
      return IOGroovyMethods.getText(getClass().getResourceAsStream(resourceName));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
