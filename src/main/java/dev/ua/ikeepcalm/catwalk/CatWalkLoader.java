package dev.ua.ikeepcalm.catwalk;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
public class CatWalkLoader implements PluginLoader {

    @Override
    public void classloader(@NotNull PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", "https://repo1.maven.org/maven2/"
        ).build());

        List<Dependency> dependencies = List.of(
                // Core Javalin web framework
                new Dependency(new DefaultArtifact("io.javalin:javalin-bundle:6.6.0"), null),

                new Dependency(new DefaultArtifact("io.javalin.community.openapi:javalin-openapi-plugin:6.6.0"), null),
                new Dependency(new DefaultArtifact("io.javalin.community.ssl:ssl-plugin:6.6.0"), null),

                // Jackson JSON processing

                new Dependency(new DefaultArtifact("com.fasterxml.jackson.core:jackson-core:2.16.1"), null),
                new Dependency(new DefaultArtifact("com.fasterxml.jackson.core:jackson-databind:2.16.1"), null),
                new Dependency(new DefaultArtifact("com.fasterxml.jackson.core:jackson-annotations:2.16.1"), null),

                // Apache Commons utilities
                new Dependency(new DefaultArtifact("org.apache.commons:commons-compress:1.26.0"), null),
                new Dependency(new DefaultArtifact("commons-io:commons-io:2.14.0"), null),
                new Dependency(new DefaultArtifact("org.apache.commons:commons-lang3:3.12.0"), null),

                // Database dependencies (NEW for v0.8+)
                new Dependency(new DefaultArtifact("com.zaxxer:HikariCP:5.1.0"), null),
                new Dependency(new DefaultArtifact("org.mariadb.jdbc:mariadb-java-client:3.3.2"), null),

                // SLF4J binding for HikariCP logging
                new Dependency(new DefaultArtifact("org.slf4j:slf4j-jdk14:2.0.9"), null)
        );

        // Add all dependencies to the resolver
        dependencies.forEach(resolver::addDependency);

        // Add the resolver to the classpath builder
        classpathBuilder.addLibrary(resolver);
    }
}