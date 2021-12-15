package tools

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.stream.Collectors

class FakeJenkinsObject extends LinkedHashMap {

    def findFiles(Map args) {
        def glob = args.glob as String
        final PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:${glob}");
        def stream = Files.walk(Paths.get("."));
        try {
        return stream.
                filter {matcher.matches(it) }.
                map { it.toFile().absolutePath }.
                collect(Collectors.toList())
        } finally {
            stream.close()
        }
    }

    def readFile(String file) {
        return new File(file).text
    }

}
