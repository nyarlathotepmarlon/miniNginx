package com.example.proxy.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TargetUriTest {
    @ParameterizedTest
    @CsvSource({
        "/api,/users,/api/users", "/api/,/users,/api/users",
        "/api,/files/a%2Fb,/api/files/a%2Fb", "/api,/search?q=a%2Bb&x=+%25,/api/search?q=a%2Bb&x=+%25",
        "/api,//files,/api//files", "/api,/a/../b,/api/a/../b",
        "/api/,/a/./b,/api/a/./b", "/api%2Fv1,/%E4%B8%AD,/api%2Fv1/%E4%B8%AD",
        "/api,/x?,/api/x?", "/,//files/a,//files/a", "/,/search,/search"
    })
    void joinsWithoutChangingRawComponents(String basePath, String target, String expected) {
        URI result = TargetUri.resolve(URI.create("http://127.0.0.1:9001" + basePath), URI.create(target));
        URI expectedUri = URI.create("http://127.0.0.1:9001" + expected);
        assertEquals(expectedUri.toString(), result.toString());
        assertEquals(expectedUri.getRawPath(), result.getRawPath());
        assertEquals(expectedUri.getRawQuery(), result.getRawQuery());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://other.example/path", "*", "/path#fragment", "relative"})
    void rejectsNonOriginFormAndFragments(String target) {
        assertThrows(IllegalArgumentException.class,
                () -> TargetUri.resolve(URI.create("http://localhost"), URI.create(target)));
    }
}
