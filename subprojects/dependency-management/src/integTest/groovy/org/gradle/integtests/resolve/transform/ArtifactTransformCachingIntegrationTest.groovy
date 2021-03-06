/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.integtests.resolve.transform

import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import org.gradle.test.fixtures.file.TestFile
import spock.lang.Unroll

import java.util.regex.Pattern

class ArtifactTransformCachingIntegrationTest extends AbstractDependencyResolutionTest {
    def setup() {
        settingsFile << """
            rootProject.name = 'root'
            include 'lib'
            include 'util'
            include 'app'
        """

        buildFile << """
def usage = Attribute.of('usage', String)
def artifactType = Attribute.of('artifactType', String)
    
allprojects {
    dependencies {
        attributesSchema {
            attribute(usage)
        }
    }
    configurations {
        compile {
            attributes.attribute usage, 'api'
        }
    }
}

"""
    }

    def "transform is applied to each file once per build"() {
        given:
        buildFile << """
            allprojects {
                dependencies {
                    registerTransform {
                        from.attribute(artifactType, "jar")
                        to.attribute(artifactType, "size")
                        artifactTransform(FileSizer)
                    }
                }
                task resolve {
                    def artifacts = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'size') }.artifacts
                    inputs.files artifacts.artifactFiles
                    doLast {
                        println "files 1: " + artifacts.artifactFiles.collect { it.name }
                        println "files 2: " + artifacts.collect { it.file.name }
                        println "ids 1: " + artifacts.collect { it.id.displayName }
                        println "components 1: " + artifacts.collect { it.id.componentIdentifier }
                    }
                }
            }

            class FileSizer extends ArtifactTransform {
                List<File> transform(File input) {
                    assert outputDirectory.directory && outputDirectory.list().length == 0
                    def output = new File(outputDirectory, input.name + ".txt")
                    println "Transforming \$input.name to \$output.name into \$outputDirectory"
                    output.text = String.valueOf(input.length())
                    return [output]
                }
            }

            project(':lib') {
                task jar1(type: Jar) {            
                    archiveName = 'lib1.jar'
                }
                task jar2(type: Jar) {            
                    archiveName = 'lib2.jar'
                }
                artifacts {
                    compile jar1
                    compile jar2
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files 1: [lib1.jar.txt, lib2.jar.txt]") == 2
        output.count("files 2: [lib1.jar.txt, lib2.jar.txt]") == 2
        output.count("ids 1: [lib1.jar.txt (project :lib), lib2.jar.txt (project :lib)]") == 2
        output.count("components 1: [project :lib, project :lib]") == 2

        output.count("Transforming") == 2
        isTransformed("lib1.jar", "lib1.jar.txt")
        isTransformed("lib2.jar", "lib2.jar.txt")
    }

    def "each file is transformed once per set of configuration parameters"() {
        given:
        buildFile << """
            class TransformWithMultipleTargets extends ArtifactTransform {
                private String target
                
                TransformWithMultipleTargets(String target) {
                    this.target = target
                }
                
                List<File> transform(File input) {
                    if (target.equals("size")) {
                        def outSize = new File(outputDirectory, input.name + ".size")
                        println "Transforming \$input.name to \$outSize.name into \$outputDirectory"
                        outSize.text = String.valueOf(input.length())
                        return [outSize]
                    }
                    if (target.equals("hash")) {
                        def outHash = new File(outputDirectory, input.name + ".hash")
                        println "Transforming \$input.name to \$outHash.name into \$outputDirectory"
                        outHash.text = 'hash'
                        return [outHash]
                    }             
                }
            }
            
            allprojects {
                dependencies {
                    registerTransform {
                        from.attribute(artifactType, 'jar')
                        to.attribute(artifactType, 'size')
                        artifactTransform(TransformWithMultipleTargets) {
                            params('size')
                        }
                    }
                    registerTransform {
                        from.attribute(artifactType, 'jar')
                        to.attribute(artifactType, 'hash')
                        artifactTransform(TransformWithMultipleTargets) {
                            params('hash')
                        }
                    }
                }
                task resolve {
                    doLast {
                        def size = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'size') }.artifacts
                        def hash = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'hash') }.artifacts
                        println "files 1: " + size.collect { it.file.name }
                        println "ids 1: " + size.collect { it.id }
                        println "components 1: " + size.collect { it.id.componentIdentifier }
                        println "files 2: " + hash.collect { it.file.name }
                        println "ids 2: " + hash.collect { it.id }
                        println "components 2: " + hash.collect { it.id.componentIdentifier }
                    }
                }
            }

            project(':lib') {
                task jar1(type: Jar) {            
                    archiveName = 'lib1.jar'
                }
                task jar2(type: Jar) {            
                    archiveName = 'lib2.jar'
                }
                artifacts {
                    compile jar1
                    compile jar2
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files 1: [lib1.jar.size, lib2.jar.size]") == 2
        output.count("ids 1: [lib1.jar.size (project :lib), lib2.jar.size (project :lib)]") == 2
        output.count("components 1: [project :lib, project :lib]") == 2
        output.count("files 2: [lib1.jar.hash, lib2.jar.hash]") == 2
        output.count("ids 2: [lib1.jar.hash (project :lib), lib2.jar.hash (project :lib)]") == 2
        output.count("components 2: [project :lib, project :lib]") == 2

        output.count("Transforming") == 4
        isTransformed("lib1.jar", "lib1.jar.size")
        isTransformed("lib2.jar", "lib2.jar.size")
        isTransformed("lib1.jar", "lib1.jar.hash")
        isTransformed("lib2.jar", "lib2.jar.hash")
    }

    def "can use custom type that does not implement equals() for transform configuration"() {
        given:
        buildFile << """
            class CustomType implements Serializable {
                String value
            }
            
            class TransformWithMultipleTargets extends ArtifactTransform {
                private CustomType target
                
                TransformWithMultipleTargets(CustomType target) {
                    this.target = target
                }
                
                List<File> transform(File input) {
                    if (target.value == "size") {
                        def outSize = new File(outputDirectory, input.name + ".size")
                        println "Transforming \$input.name to \$outSize.name into \$outputDirectory"
                        outSize.text = String.valueOf(input.length())
                        return [outSize]
                    }
                    if (target.value == "hash") {
                        def outHash = new File(outputDirectory, input.name + ".hash")
                        println "Transforming \$input.name to \$outHash.name into \$outputDirectory"
                        outHash.text = 'hash'
                        return [outHash]
                    }             
                }
            }
            
            allprojects {
                dependencies {
                    registerTransform {
                        from.attribute(artifactType, 'jar')
                        to.attribute(artifactType, 'size')
                        artifactTransform(TransformWithMultipleTargets) {
                            params(new CustomType(value: 'size'))
                        }
                    }
                    registerTransform {
                        from.attribute(artifactType, 'jar')
                        to.attribute(artifactType, 'hash')
                        artifactTransform(TransformWithMultipleTargets) {
                            params(new CustomType(value: 'hash'))
                        }
                    }
                }
                task resolve {
                    doLast {
                        def size = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'size') }.artifacts
                        def hash = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'hash') }.artifacts
                        println "files 1: " + size.collect { it.file.name }
                        println "files 2: " + hash.collect { it.file.name }
                    }
                }
            }

            project(':lib') {
                task jar1(type: Jar) {            
                    archiveName = 'lib1.jar'
                }
                task jar2(type: Jar) {            
                    archiveName = 'lib2.jar'
                }
                artifacts {
                    compile jar1
                    compile jar2
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files 1: [lib1.jar.size, lib2.jar.size]") == 2
        output.count("files 2: [lib1.jar.hash, lib2.jar.hash]") == 2

        output.count("Transforming") == 4
        isTransformed("lib1.jar", "lib1.jar.size")
        isTransformed("lib2.jar", "lib2.jar.size")
        isTransformed("lib1.jar", "lib1.jar.hash")
        isTransformed("lib2.jar", "lib2.jar.hash")
    }

    @Unroll
    def "can use configuration parameter of type #type"() {
        given:
        buildFile << """
            class TransformWithMultipleTargets extends ArtifactTransform {
                private $type target
                
                TransformWithMultipleTargets($type target) {
                    this.target = target
                }
                
                List<File> transform(File input) {
                    def outSize = new File(outputDirectory, input.name + ".value")
                    println "Transforming \$input.name to \$outSize.name into \$outputDirectory"
                    outSize.text = String.valueOf(input.length()) + String.valueOf(target)
                    return [outSize]
                }
            }
            
            allprojects {
                dependencies {
                    registerTransform {
                        from.attribute(artifactType, 'jar')
                        to.attribute(artifactType, 'value')
                        artifactTransform(TransformWithMultipleTargets) {
                            params($value)
                        }
                    }
                }
                task resolve {
                    doLast {
                        def values = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'value') }.artifacts
                        println "files 1: " + values.collect { it.file.name }
                        println "files 2: " + values.collect { it.file.name }
                    }
                }
            }

            project(':lib') {
                task jar1(type: Jar) {            
                    archiveName = 'lib1.jar'
                }
                task jar2(type: Jar) {            
                    archiveName = 'lib2.jar'
                }
                artifacts {
                    compile jar1
                    compile jar2
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files 1: [lib1.jar.value, lib2.jar.value]") == 2
        output.count("files 2: [lib1.jar.value, lib2.jar.value]") == 2

        output.count("Transforming") == 2
        isTransformed("lib1.jar", "lib1.jar.value")
        isTransformed("lib2.jar", "lib2.jar.value")

        where:
        type           | value
        "boolean"      | "true"
        "int"          | "123"
        "List<Object>" | "[123, 'abc']"
    }

    def "each file is transformed once per transform class"() {
        given:
        buildFile << """
            class Sizer extends ArtifactTransform {
                Sizer(String target) {
                    // ignore config
                }
                
                List<File> transform(File input) {
                    def outSize = new File(outputDirectory, input.name + ".size")
                    println "Transforming \$input.name to \$outSize.name into \$outputDirectory"
                    outSize.text = String.valueOf(input.length())
                    return [outSize]
                }
            }
            class Hasher extends ArtifactTransform {
                private String target
                
                Hasher(String target) {
                    // ignore config
                }
                
                List<File> transform(File input) {
                    def outHash = new File(outputDirectory, input.name + ".hash")
                    println "Transforming \$input.name to \$outHash.name into \$outputDirectory"
                    outHash.text = 'hash'
                    return [outHash]
                }
            }
            
            allprojects {
                dependencies {
                    registerTransform {
                        from.attribute(artifactType, 'jar')
                        to.attribute(artifactType, 'size')
                        artifactTransform(Sizer) { params('size') }
                    }
                    registerTransform {
                        from.attribute(artifactType, 'jar')
                        to.attribute(artifactType, 'hash')
                        artifactTransform(Hasher) { params('hash') }
                    }
                }
                task resolve {
                    doLast {
                        def size = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'size') }.artifacts
                        def hash = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'hash') }.artifacts
                        println "files 1: " + size.collect { it.file.name }
                        println "ids 1: " + size.collect { it.id }
                        println "components 1: " + size.collect { it.id.componentIdentifier }
                        println "files 2: " + hash.collect { it.file.name }
                        println "ids 2: " + hash.collect { it.id }
                        println "components 2: " + hash.collect { it.id.componentIdentifier }
                    }
                }
            }

            project(':lib') {
                task jar1(type: Jar) {            
                    archiveName = 'lib1.jar'
                }
                task jar2(type: Jar) {            
                    archiveName = 'lib2.jar'
                }
                artifacts {
                    compile jar1
                    compile jar2
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files 1: [lib1.jar.size, lib2.jar.size]") == 2
        output.count("ids 1: [lib1.jar.size (project :lib), lib2.jar.size (project :lib)]") == 2
        output.count("components 1: [project :lib, project :lib]") == 2
        output.count("files 2: [lib1.jar.hash, lib2.jar.hash]") == 2
        output.count("ids 2: [lib1.jar.hash (project :lib), lib2.jar.hash (project :lib)]") == 2
        output.count("components 2: [project :lib, project :lib]") == 2

        output.count("Transforming") == 4
        isTransformed("lib1.jar", "lib1.jar.size")
        isTransformed("lib2.jar", "lib2.jar.size")
        isTransformed("lib1.jar", "lib1.jar.hash")
        isTransformed("lib2.jar", "lib2.jar.hash")
    }

    def "transform is supplied with a different output directory when input file content changes"() {
        given:
        buildFile << """
            allprojects {
                dependencies {
                    registerTransform {
                        to.attribute(artifactType, "size")
                        artifactTransform(FileSizer)
                    }
                }
                task resolve {
                    def artifacts = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'size') }.artifacts
                    inputs.files artifacts.artifactFiles
                    doLast {
                        println "files: " + artifacts.artifactFiles.collect { it.name }
                    }
                }
            }

            class FileSizer extends ArtifactTransform {
                List<File> transform(File input) {
                    def output = new File(outputDirectory, input.name + ".txt")
                    println "Transforming \$input.name to \$output.name into \$outputDirectory"
                    output.text = "transformed"
                    return [output]
                }
            }

            project(':lib') {
                dependencies {
                    compile files("lib1.jar")
                }
                artifacts {
                    compile file("dir1.classes")
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        file("lib/lib1.jar").text = "123"
        file("lib/dir1.classes").file("child").createFile()

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files: [lib1.jar.txt, dir1.classes.txt]") == 2

        output.count("Transforming") == 2
        isTransformed("dir1.classes", "dir1.classes.txt")
        isTransformed("lib1.jar", "lib1.jar.txt")
        def outputDir1 = outputDir("dir1.classes", "dir1.classes.txt")
        def outputDir2 = outputDir("lib1.jar", "lib1.jar.txt")

        when:
        file("lib/lib1.jar").text = "abc"
        file("lib/dir1.classes").file("child2").createFile()

        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files: [lib1.jar.txt, dir1.classes.txt]") == 2

        output.count("Transforming") == 2
        isTransformed("dir1.classes", "dir1.classes.txt")
        isTransformed("lib1.jar", "lib1.jar.txt")
        outputDir("dir1.classes", "dir1.classes.txt") != outputDir1
        outputDir("lib1.jar", "lib1.jar.txt") != outputDir2
    }

    def "transform is supplied with a different output directory when transform implementation changes"() {
        given:
        buildFile << """
            allprojects {
                dependencies {
                    registerTransform {
                        to.attribute(artifactType, "size")
                        artifactTransform(FileSizer)
                    }
                }
                task resolve {
                    def artifacts = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'size') }.artifacts
                    inputs.files artifacts.artifactFiles
                    doLast {
                        println "files: " + artifacts.artifactFiles.collect { it.name }
                    }
                }
            }

            class FileSizer extends ArtifactTransform {
                List<File> transform(File input) {
                    def output = new File(outputDirectory, input.name + ".txt")
                    println "Transforming \$input.name to \$output.name into \$outputDirectory"
                    output.text = "transformed"
                    return [output]
                }
            }

            project(':lib') {
                artifacts {
                    compile file("dir1.classes")
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        file("lib/dir1.classes").file("child").createFile()

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files: [dir1.classes.txt]") == 2

        output.count("Transforming") == 1
        isTransformed("dir1.classes", "dir1.classes.txt")
        def outputDir1 = outputDir("dir1.classes", "dir1.classes.txt")

        when:
        buildFile.replace('output.text = "transformed"', 'output.text = "new output"')
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files: [dir1.classes.txt]") == 2

        output.count("Transforming") == 1
        isTransformed("dir1.classes", "dir1.classes.txt")
        outputDir("dir1.classes", "dir1.classes.txt") != outputDir1
    }

    def "transform is supplied with a different output directory when configuration parameters change"() {
        given:
        // Use another script to define the value, so that transform implementation does not change when the value is changed
        def otherScript = file("other.gradle")
        otherScript.text = "ext.value = 123"

        buildFile << """
            apply from: 'other.gradle'
            allprojects {
                dependencies {
                    registerTransform {
                        to.attribute(artifactType, "size")
                        artifactTransform(FileSizer) { params(value) }
                    }
                }
                task resolve {
                    def artifacts = configurations.compile.incoming.artifactView().attributes { it.attribute(artifactType, 'size') }.artifacts
                    inputs.files artifacts.artifactFiles
                    doLast {
                        println "files: " + artifacts.artifactFiles.collect { it.name }
                    }
                }
            }

            class FileSizer extends ArtifactTransform {
                FileSizer(Number n) { }
                
                List<File> transform(File input) {
                    def output = new File(outputDirectory, input.name + ".txt")
                    println "Transforming \$input.name to \$output.name into \$outputDirectory"
                    output.text = "transformed"
                    return [output]
                }
            }

            project(':lib') {
                artifacts {
                    compile file("dir1.classes")
                }
            }
            
            project(':util') {
                dependencies {
                    compile project(':lib')
                }
            }

            project(':app') {
                dependencies {
                    compile project(':util')
                }
            }
        """

        file("lib/dir1.classes").file("child").createFile()

        when:
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files: [dir1.classes.txt]") == 2

        output.count("Transforming") == 1
        isTransformed("dir1.classes", "dir1.classes.txt")
        def outputDir1 = outputDir("dir1.classes", "dir1.classes.txt")

        when:
        otherScript.replace('123', '123.4')
        succeeds ":util:resolve", ":app:resolve"

        then:
        output.count("files: [dir1.classes.txt]") == 2

        output.count("Transforming") == 1
        isTransformed("dir1.classes", "dir1.classes.txt")
        outputDir("dir1.classes", "dir1.classes.txt") != outputDir1
    }

    void isTransformed(String from, String to) {
        def dirs = outputDirs(from, to)
        if (dirs.size() == 0) {
            throw new AssertionError("Could not find $from -> $to in output: $output")
        }
        if (dirs.size() > 1) {
            throw new AssertionError("Found $from -> $to more than once in output: $output")
        }
        assert output.count("into " + dirs.first()) == 1
    }

    TestFile outputDir(String from, String to) {
        def dirs = outputDirs(from, to)
        if (dirs.size() == 1) {
            return dirs.first()
        }
        throw new AssertionError("Could not find exactly one output directory for $from -> $to in output: $output")
    }

    List<TestFile> outputDirs(String from, String to) {
        List<TestFile> dirs = []
        def baseDir = executer.gradleUserHomeDir.file("/caches/transforms-1/" + from).absolutePath + File.separator
        def pattern = Pattern.compile("Transforming " + Pattern.quote(from) + " to " + Pattern.quote(to) + " into (" + Pattern.quote(baseDir) + "\\w+)")
        for (def line : output.readLines()) {
            def matcher = pattern.matcher(line)
            if (matcher.matches()) {
                dirs.add(new TestFile(matcher.group(1)))
            }
        }
        return dirs
    }

}
