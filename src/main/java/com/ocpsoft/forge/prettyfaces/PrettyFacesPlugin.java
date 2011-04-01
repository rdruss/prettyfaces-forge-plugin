package com.ocpsoft.forge.prettyfaces;

import java.io.IOException;
import java.util.List;

import javax.enterprise.event.Event;
import javax.inject.Inject;

import org.jboss.seam.forge.parser.xml.XMLParser;
import org.jboss.seam.forge.project.Project;
import org.jboss.seam.forge.project.facets.FacetInstallationAborted;
import org.jboss.seam.forge.resources.FileResource;
import org.jboss.seam.forge.shell.ShellMessages;
import org.jboss.seam.forge.shell.events.InstallFacets;
import org.jboss.seam.forge.shell.plugins.Alias;
import org.jboss.seam.forge.shell.plugins.Command;
import org.jboss.seam.forge.shell.plugins.DefaultCommand;
import org.jboss.seam.forge.shell.plugins.Option;
import org.jboss.seam.forge.shell.plugins.PipeOut;
import org.jboss.seam.forge.shell.plugins.Plugin;
import org.jboss.seam.forge.shell.plugins.RequiresProject;
import org.jboss.seam.forge.spec.jsf.FacesFacet;
import org.jboss.shrinkwrap.descriptor.api.Node;
import org.jboss.shrinkwrap.descriptor.impl.base.Strings;
import org.xml.sax.SAXException;

import com.ocpsoft.pretty.faces.config.PrettyConfig;
import com.ocpsoft.pretty.faces.config.mapping.UrlMapping;
import com.ocpsoft.pretty.faces.event.MultiPageMessagesSupport;
import com.ocpsoft.pretty.faces.url.URLPatternParser;

@RequiresProject
@Alias("prettyfaces")
public class PrettyFacesPlugin implements Plugin
{
   private final Project project;
   private final Event<InstallFacets> installFacets;

   @Inject
   public PrettyFacesPlugin(Project project, Event<InstallFacets> event)
   {
      this.project = project;
      this.installFacets = event;
   }

   @DefaultCommand
   public void status(PipeOut out)
   {
      if (project.hasFacet(PrettyFacesFacet.class))
      {
         out.println("PrettyFaces is installed.");
      }
      else
      {
         out.println("PrettyFaces is not installed. Use 'prettyfaces setup' to get started.");
      }
   }

   // confirmed working
   @Command("setup")
   public void setup(PipeOut out)
   {
      if (!project.hasFacet(PrettyFacesFacet.class))
      {
         installFacets.fire(new InstallFacets(PrettyFacesFacet.class));
      }
      if (project.hasFacet(PrettyFacesFacet.class))
         ShellMessages.success(out, "PrettyFaces is configured.");
   }

   @Command("map-url")
   public void mapUrl(
            PipeOut out,
            @Option(name = "pattern", description = "the URL pattern", required = true) String pattern,
            @Option(name = "viewId", description = "the server resource to be displayed", required = true) String viewId,
            @Option(name = "id", description = "the mapping id") String id,
            @Option(name = "parentId", description = "parent mapping to inherit from") String parentId,
            boolean inbound) throws IOException, SAXException
   {
      assertInstalled();

      PrettyFacesFacet pf = project.getFacet(PrettyFacesFacet.class);
      PrettyConfig prettyConfig = pf.getPrettyConfig();
      List<UrlMapping> mappings = prettyConfig.getMappings();

      // verify not already mapped
      for (UrlMapping m : mappings)
      {
         if (m.getPattern().equals(pattern))
         {
            throw new RuntimeException("Pattern is already mapped by the " + m.toString());
         }
      }

      Node config = pf.getConfig();
      Node m = config.create("url-mapping");
      if (id != null)
      {
         m.attribute("id", id);
      }
      m.create("pattern").attribute("value", pattern);
      m.create("view-id").attribute("value", viewId);

      if (parentId != null)
      {
         UrlMapping parent = prettyConfig.getMappingById(parentId);
         if (parent == null)
         {
            throw new RuntimeException("No parent with id of [" + parentId + "]");
         }
         // validate the pattern
         new URLPatternParser(parent.getPattern() + pattern);
         m.attribute("parentId", parentId);
      }
      new URLPatternParser(pattern);

      pf.saveConfig(config);
   }

   @Command("list-mappings")
   public void listMappings(PipeOut out) throws IOException, SAXException
   {
      assertInstalled();
      PrettyFacesFacet pf = project.getFacet(PrettyFacesFacet.class);
      PrettyConfig config = pf.getPrettyConfig();

      List<UrlMapping> mappings = config.getMappings();
      for (UrlMapping m : mappings)
      {
         out.println(m.getPattern() + " -> " + m.getViewId()
                  + " [" + (Strings.isNullOrEmpty(m.getId()) ? "" : "id=" + m.getId() + ", ")
                  + (Strings.isNullOrEmpty(m.getParentId()) ? "" : "id=" + m.getId() + ", ")
                  + "outbound=" + m.isOutbound() + "]");
      }

      if (mappings.isEmpty())
      {
         ShellMessages.info(out, "Nothing to list.");
      }
   }

   // confirmed working
   @Command("faces-message-propagation")
   public void multiPageMessagesSupport(PipeOut out, Action action)
   {
      assertInstalled();

      switch (action)
      {
      case INSTALL:
         try
         {
            if (!project.hasFacet(FacesFacet.class))
            {
               ShellMessages.info(out, "This feature requires JavaServer(tm) Faces.");
               installFacets.fire(new InstallFacets(true, FacesFacet.class));
            }

            FacesFacet facesFacet = project.getFacet(FacesFacet.class);
            FileResource<?> facesConfigFile = facesFacet.getConfigFile();
            Node facesConfig = XMLParser.parse(facesConfigFile.getResourceInputStream());

            Node lifecycle = facesConfig.getOrCreate("lifecycle");
            List<Node> list = lifecycle.get("phase-listener");
            Node messagesListener = null;
            for (Node n : list)
            {
               if (MultiPageMessagesSupport.class.getName().equals(n.text()))
               {
                  messagesListener = n;
               }
            }

            if (messagesListener == null)
            {
               messagesListener = lifecycle.create("phase-listener").text(MultiPageMessagesSupport.class.getName());
               facesConfigFile.setContents(XMLParser.toXMLString(facesConfig));
            }
            ShellMessages.success(out, "MultiPageMessagesListener is installed.");
         }
         catch (FacetInstallationAborted e)
         {
         }

         break;
      case REMOVE:

         if (project.hasFacet(FacesFacet.class))
         {
            FacesFacet facesFacet = project.getFacet(FacesFacet.class);
            FileResource<?> facesConfigFile = facesFacet.getConfigFile();
            Node facesConfig = XMLParser.parse(facesConfigFile.getResourceInputStream());

            Node lifecycle = facesConfig.getSingle("lifecycle");
            if (lifecycle != null)
            {
               List<Node> list = lifecycle.get("phase-listener");
               Node messagesListener = null;
               for (Node n : list)
               {
                  if (MultiPageMessagesSupport.class.getName().equals(n.text()))
                  {
                     messagesListener = n;
                  }
               }

               if (messagesListener != null)
               {
                  lifecycle.removeSingle(messagesListener);
                  if (lifecycle.children().isEmpty())
                  {
                     facesConfig.removeSingle(lifecycle);
                  }
                  facesConfigFile.setContents(XMLParser.toXMLString(facesConfig));
                  ShellMessages.success(out, "Removed MultiPageMessagesListener.");
                  return;
               }
            }
            ShellMessages.info(out, "No action required. MultiPageMessagesListener not installed.");
         }
         else
         {
            ShellMessages.info(out, "No action required. JSF is not installed.");
         }
         break;
      }

   }

   private void assertInstalled()
   {
      if (!project.hasFacet(PrettyFacesFacet.class))
      {
         throw new RuntimeException("PrettyFaces is not installed. Use 'prettyfaces setup' to get started.");
      }
   }
}
