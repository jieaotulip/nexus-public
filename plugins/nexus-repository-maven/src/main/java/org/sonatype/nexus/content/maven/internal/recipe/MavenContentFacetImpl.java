/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.content.maven.internal.recipe;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.content.maven.internal.event.RebuildMavenArchetypeCatalogEvent;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.ConfigurationFacet;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.RepositoryContent;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.facet.WritePolicy;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentAssetBuilder;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.maven.LayoutPolicy;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.Coordinates;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.internal.validation.MavenMetadataContentValidator;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.collect.ImmutableList;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.model.Model;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.common.hash.HashAlgorithm.SHA1;
import static org.sonatype.nexus.content.maven.internal.recipe.AttributesHelper.P_ASSET_KIND;
import static org.sonatype.nexus.content.maven.internal.recipe.AttributesHelper.assetKind;
import static org.sonatype.nexus.content.maven.internal.recipe.AttributesHelper.getPackaging;
import static org.sonatype.nexus.content.maven.internal.recipe.AttributesHelper.setAssetAttributes;
import static org.sonatype.nexus.content.maven.internal.recipe.AttributesHelper.setComponentAttributes;
import static org.sonatype.nexus.content.maven.internal.recipe.AttributesHelper.setPomAttributes;
import static org.sonatype.nexus.content.maven.internal.recipe.MavenArchetypeCatalogFacetImpl.MAVEN_ARCHETYPE_KIND;
import static org.sonatype.nexus.repository.content.facet.WritePolicy.ALLOW;
import static org.sonatype.nexus.repository.content.facet.WritePolicy.ALLOW_ONCE;
import static org.sonatype.nexus.repository.maven.internal.Attributes.AssetKind.REPOSITORY_INDEX;
import static org.sonatype.nexus.repository.maven.internal.Attributes.AssetKind.REPOSITORY_METADATA;
import static org.sonatype.nexus.repository.maven.internal.Constants.METADATA_FILENAME;
import static org.sonatype.nexus.repository.maven.internal.MavenModels.readModel;

/**
 * A {@link MavenContentFacet} that persists to a {@link ContentFacet}.
 *
 * @since 3.next
 */
@Named(Maven2Format.NAME)
public class MavenContentFacetImpl
    extends ContentFacetSupport
    implements MavenContentFacet
{
  private static final String CONFIG_KEY = "maven";

  private static final List<HashAlgorithm> HASHING = ImmutableList.of(SHA1, MD5);

  private final Map<String, MavenPathParser> mavenPathParsers;

  private final MavenMetadataContentValidator metadataValidator;

  private final EventManager eventManager;

  private final boolean metadataValidationEnabled;

  private Config config;

  private MavenPathParser mavenPathParser;

  static class Config
  {
    @NotNull(groups = {HostedType.ValidationGroup.class, ProxyType.ValidationGroup.class})
    public VersionPolicy versionPolicy;

    @NotNull(groups = {HostedType.ValidationGroup.class, ProxyType.ValidationGroup.class})
    public LayoutPolicy layoutPolicy;

    @Override
    public String toString() {
      return getClass().getSimpleName() + "{" +
          "versionPolicy=" + versionPolicy +
          ", layoutPolicy=" + layoutPolicy +
          '}';
    }
  }

  @Inject
  public MavenContentFacetImpl(
      @Named(Maven2Format.NAME) final FormatStoreManager formatStoreManager,
      final Map<String, MavenPathParser> mavenPathParsers,
      final MavenMetadataContentValidator metadataValidator,
      final EventManager eventManager,
      @Named("${nexus.maven.metadata.validation.enabled:-true}") final boolean metadataValidationEnabled)
  {
    super(formatStoreManager);
    this.mavenPathParsers = checkNotNull(mavenPathParsers);
    this.metadataValidator = metadataValidator;
    this.eventManager = eventManager;
    this.metadataValidationEnabled = metadataValidationEnabled;
  }

  @Override
  public MavenPathParser getMavenPathParser() {
    return mavenPathParser;
  }

  @Override
  public LayoutPolicy layoutPolicy() {
    return config.layoutPolicy;
  }

  @Override
  public VersionPolicy getVersionPolicy() {
    return config.versionPolicy;
  }

  @Override
  protected void doInit(final Configuration configuration) throws Exception {
    super.doInit(configuration);
    mavenPathParser = checkNotNull(mavenPathParsers.get(getRepository().getFormat().getValue()));
  }

  @Override
  protected void doConfigure(final Configuration configuration) throws Exception {
    super.doConfigure(configuration);
    config = facet(ConfigurationFacet.class).readSection(configuration, CONFIG_KEY, MavenContentFacetImpl.Config.class);
    log.debug("Config: {}", config);
  }

  @Override
  protected WritePolicy writePolicy(final Asset asset) {
    WritePolicy configuredWritePolicy = super.writePolicy(asset);
    if (ALLOW_ONCE == configuredWritePolicy) {
      String assetKind = asset.kind();
      if (StringUtils.equals(REPOSITORY_METADATA.name(), assetKind)
          || StringUtils.equals(REPOSITORY_INDEX.name(), assetKind)) {
        return ALLOW;
      }
    }
    return configuredWritePolicy;
  }

  @Override
  public Optional<Content> get(final String path) {
    log.debug("GET {} : {}", getRepository().getName(), path);

    return findAsset(path)
        .map(FluentAsset::download);
  }

  @Override
  public Content put(final MavenPath path, final Payload content) throws IOException {
    log.debug("PUT {} : {}", getRepository().getName(), path);

    try (TempBlob blob = blobs().ingest(content, HASHING)) {
      if (isMetadataAndValidationEnabled(path)) {
        validate(path, blob);
      }
      return save(path, content, blob);
    }
  }

  private Optional<FluentAsset> findAsset(final String path) {
    return assets()
        .path(path)
        .find();
  }

  private boolean isMetadataAndValidationEnabled(final MavenPath path) {
    return path.getFileName().equals(METADATA_FILENAME) && metadataValidationEnabled;
  }

  private void validate(final MavenPath mavenPath, final TempBlob blob) {
    log.debug("Validating maven-metadata.xml before storing");
    metadataValidator.validate(mavenPath.getPath(), blob.get());
  }

  private Content save(final MavenPath mavenPath, final Payload content, final TempBlob blob) throws IOException {
    FluentComponent component = null;
    if (mavenPath.getCoordinates() != null) {
      component = createOrGetComponent(mavenPath);
      maybeUpdateComponentAttributesFromModel(component, mavenPath, blob);
    }
    return createOrUpdateAsset(mavenPath, component, content, blob);
  }

  private FluentComponent createOrGetComponent(final MavenPath mavenPath)
  {
    Coordinates coordinates = mavenPath.getCoordinates();
    FluentComponent component = components()
        .name(coordinates.getArtifactId())
        .namespace(coordinates.getGroupId())
        .version(coordinates.getVersion())
        .getOrCreate();
    if (isNewRepositoryContent(component)) {
      setComponentAttributes(component, coordinates);
    }
    return component;
  }

  private boolean isNewRepositoryContent(final RepositoryContent repositoryContent) {
    return repositoryContent.attributes().isEmpty();
  }

  private void maybeUpdateComponentAttributesFromModel(
      final FluentComponent component, final MavenPath mavenPath,
      final TempBlob blob) throws IOException
  {
    Model model = maybeReadMavenModel(mavenPath, blob);
    if (model != null) {
      component.kind(getPackaging(model));
      setPomAttributes(component, model);
      publishEvents(component);
    }
  }

  private void publishEvents(final FluentComponent component) {
    if (MAVEN_ARCHETYPE_KIND.equals(component.kind())) {
      eventManager.post(new RebuildMavenArchetypeCatalogEvent(getRepository().getName()));
    }
  }

  private Model maybeReadMavenModel(final MavenPath mavenPath, final TempBlob blob) throws IOException
  {
    Model model = null;
    if (mavenPath.isPom()) {
      model = readModel(blob.getBlob().getInputStream());
      if (model == null) {
        log.warn("Could not parse POM: {} @ {}", getRepository().getName(), mavenPath.getPath());
      }
    }
    return model;
  }

  private Content createOrUpdateAsset(
      final MavenPath path,
      final Component component,
      final Payload content,
      final TempBlob blob)
  {
    FluentAssetBuilder assetBuilder = assets().path(path.getPath()).kind(assetKind(path, mavenPathParser));
    if (component != null) {
      assetBuilder = assetBuilder.component(component);
    }

    FluentAsset asset = assetBuilder.getOrCreate();
    if (isNewRepositoryContent(asset)) {
      setAssetAttributes(asset, path, mavenPathParser);
    }
    return asset.attach(blob)
        .markAsCached(content)
        .download();
  }

  @Override
  public boolean delete(final MavenPath mavenPath) {
    log.trace("DELETE {} : {}", getRepository().getName(), mavenPath);
    boolean assetIsDeleted = deleteAsset(mavenPath);
    if (assetIsDeleted && mavenPath.getCoordinates() != null) {
      maybeDeleteComponent(mavenPath.getCoordinates());
    }
    return assetIsDeleted;
  }

  private boolean deleteAsset(final MavenPath mavenPath) {
    return findAsset(mavenPath.getPath())
        .map(FluentAsset::delete)
        .orElse(false);
  }

  private void maybeDeleteComponent(final Coordinates coordinates) {
    components()
        .name(coordinates.getArtifactId())
        .namespace(coordinates.getGroupId())
        .version(coordinates.getVersion())
        .find()
        .ifPresent(this::deleteIfNoAssetsLeft);
  }

  private void deleteIfNoAssetsLeft(final FluentComponent component) {
    if (component.assets().isEmpty()) {
      component.delete();
      publishEvents(component);
    }
  }
}
