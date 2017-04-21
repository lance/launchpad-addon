/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package io.openshift.launchpad.ui.booster;

import java.util.Map;

import javax.inject.Inject;

import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.jboss.forge.addon.maven.projects.MavenBuildSystem;
import org.jboss.forge.addon.maven.resources.MavenModelResource;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.ProjectFactory;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.addon.ui.wizard.UIWizardStep;

import io.openshift.launchpad.catalog.Booster;
import io.openshift.launchpad.catalog.BoosterCatalogService;
import io.openshift.launchpad.catalog.Mission;
import io.openshift.launchpad.catalog.Runtime;
import io.openshift.launchpad.ui.input.ProjectName;

/**
 *
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
public class MetadataStep implements UIWizardStep
{
   @Inject
   private BoosterCatalogService catalogService;

   @Inject
   private ProjectFactory projectFactory;

   @Inject
   private MavenBuildSystem mavenBuildSystem;

   @Inject
   private ProjectName named;

   /**
    * Used in LaunchpadResource TODO: Check if it should be here?
    */
   @Inject
   @WithAttributes(label = "GitHub Repository Name", note = "If empty, it will assume the project name")
   private UIInput<String> gitHubRepositoryName;

   @Inject
   @WithAttributes(label = "Group Id", defaultValue = "io.openshift.booster")
   private UIInput<String> groupId;

   @Inject
   @WithAttributes(label = "Artifact Id")
   private UIInput<String> artifactId;

   @Inject
   @WithAttributes(label = "Version", required = true, defaultValue = "1.0.0-SNAPSHOT")
   private UIInput<String> version;

   @Override
   public void initializeUI(UIBuilder builder) throws Exception
   {
      UIContext context = builder.getUIContext();
      artifactId.setDefaultValue(() -> {
         Mission mission = (Mission) context.getAttributeMap().get(Mission.class);
         String missionPrefix = (mission == null) ? "" : "-" + mission.getId();
         Runtime runtime = (Runtime) context.getAttributeMap().get(Runtime.class);
         String runtimeSuffix = (runtime == null) ? "" : "-" + runtime.getId().replaceAll("\\.", "");
         return "booster" + missionPrefix + runtimeSuffix;
      });

      builder.add(named)
               .add(gitHubRepositoryName)
               .add(groupId).add(artifactId).add(version);
   }

   @Override
   public UICommandMetadata getMetadata(UIContext context)
   {
      return Metadata.forCommand(getClass()).name("Metadata")
               .description("Enter the metadata for the project")
               .category(Categories.create("Openshift.io"));
   }

   @Override
   public Result execute(UIExecutionContext context) throws Exception
   {
      Map<Object, Object> attributeMap = context.getUIContext().getAttributeMap();
      Mission mission = (Mission) attributeMap.get(Mission.class);
      Runtime runtime = (Runtime) attributeMap.get(Runtime.class);
      Booster booster = catalogService.getBooster(mission, runtime).get();
      DirectoryResource initialDir = (DirectoryResource) context.getUIContext().getInitialSelection().get();
      DirectoryResource projectDirectory = initialDir.getChildDirectory(named.getValue());
      // Using ProjectFactory to invoke bound listeners
      Project project = projectFactory.createProject(projectDirectory, mavenBuildSystem);
      // Do not cache anything
      projectFactory.invalidateCaches();
      MavenModelResource modelResource = projectDirectory.getChildOfType(MavenModelResource.class, "pom.xml");
      // Delete existing pom
      modelResource.delete();
      // Copy contents (including pom.xml if exists)
      catalogService.copy(booster, project);
      // Perform model changes
      if (modelResource.exists())
      {
         Model model = modelResource.getCurrentModel();
         model.setGroupId(groupId.getValue());
         model.setArtifactId(artifactId.getValue());
         model.setVersion(version.getValue());

         // Change child modules
         for (String module : model.getModules())
         {
            DirectoryResource moduleDirResource = projectDirectory.getChildDirectory(module);
            MavenModelResource moduleModelResource = moduleDirResource.getChildOfType(MavenModelResource.class,
                     "pom.xml");
            Model moduleModel = moduleModelResource.getCurrentModel();
            Parent parent = moduleModel.getParent();
            if (parent != null)
            {
               parent.setGroupId(model.getGroupId());
               parent.setArtifactId(model.getArtifactId());
               parent.setVersion(model.getVersion());
               moduleModelResource.setCurrentModel(moduleModel);
            }
         }
         // TODO: Change package name
         modelResource.setCurrentModel(model);
      }
      context.getUIContext().setSelection(projectDirectory);
      return Results.success();
   }

}
