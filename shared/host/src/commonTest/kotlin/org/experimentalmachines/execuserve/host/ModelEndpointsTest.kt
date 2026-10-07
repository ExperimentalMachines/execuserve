package org.experimentalmachines.execuserve.host

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelEndpointsTest {
    @Test
    fun connectionEndpointsAndServerRootsProduceTheSameModelRoutes() {
        // Addresses.endpoints returns this API-base shape, which native Copy/QR rows use.
        val endpoint = Endpoint("http://127.0.0.1:8080/v1", NetworkKind.THIS_DEVICE)
        for (base in listOf(endpoint.url, endpoint.url + "/", "http://127.0.0.1:8080", "http://127.0.0.1:8080/")) {
            assertEquals("http://127.0.0.1:8080/models/qwen3-0.6b/", ModelEndpoints.browser(base, "qwen3-0.6b"))
            assertEquals("http://127.0.0.1:8080/models/qwen3-0.6b/v1", ModelEndpoints.api(base, "qwen3-0.6b"))
            assertEquals("http://127.0.0.1:8080/models/vendor%2Fmodel/", ModelEndpoints.browser(base, "vendor/model"))
        }
    }

    @Test
    fun idsAreSingleEncodedSegmentsAndUrlsKeepTheSameOrigin() {
        assertEquals("http://[::1]:8080/models/vendor%2Fmodel%20%23%3F%C3%A9/", ModelEndpoints.browser("http://[::1]:8080/", "vendor/model #?é"))
        assertEquals("http://[::1]:8080/models/vendor%2Fmodel%20%23%3F%C3%A9/v1", ModelEndpoints.api("http://[::1]:8080/v1/", "vendor/model #?é"))
        assertEquals("http://192.168.1.2:8080/models/qwen3-0.6b/v1", ModelEndpoints.api("http://192.168.1.2:8080", "qwen3-0.6b"))
    }

    @Test
    fun theSignInLinkOpensTheChatPageWithTheKeyOnlyInTheFragment() {
        for (base in listOf("http://192.168.1.2:8091/v1", "http://192.168.1.2:8091/v1/", "http://192.168.1.2:8091")) {
            assertEquals("http://192.168.1.2:8091/", ModelEndpoints.page(base))
            assertEquals("http://192.168.1.2:8091/#key=es-abc_123", ModelEndpoints.signIn(base, "es-abc_123"))
        }
        // Anything that could end the fragment or start a parameter is encoded.
        assertEquals("http://[::1]:8091/#key=a%26b%3Dc%23d%20e", ModelEndpoints.signIn("http://[::1]:8091/v1", "a&b=c#d e"))
    }
}
