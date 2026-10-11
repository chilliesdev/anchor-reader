package com.anchorreader.chatgpt

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** This certificate and its private key are generated fixture data, never provider credentials. */
class ProviderTlsTest {
    @Test fun localTlsUsesCertificateTrustAndHostnameVerification() {
        val password = "fixture-only".toCharArray()
        val keys = KeyStore.getInstance("PKCS12").apply {
            load(Base64.getDecoder().decode(FIXTURE).inputStream(), password)
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        val server = LocalHttpFixture(serverContext).apply {
            createContext("/models") { exchange ->
                val bytes = "{\"models\":[]}".toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        try {
            val origin = "https://127.0.0.1:${server.address.port}"
            val platformTrust = PlatformChatGptHttp(setOf(origin))
            assertTrue(runCatching { platformTrust.open("$origin/models").use { readBounded(it.input) } }.isFailure)
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("fixture", keys.getCertificate(keys.aliases().nextElement()))
            }
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            val trustedContext = SSLContext.getInstance("TLS").apply { init(null, trustManagers.trustManagers, null) }
            val trusted = PlatformChatGptHttp(setOf(origin)) { url ->
                (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = trustedContext.socketFactory }
            }
            trusted.open("$origin/models").use { reply ->
                assertEquals(200, reply.status)
                assertEquals("{\"models\":[]}", readBounded(reply.input))
            }
            val wrongOrigin = "https://localhost:${server.address.port}"
            val wrongHost = PlatformChatGptHttp(setOf(wrongOrigin)) { url ->
                (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = trustedContext.socketFactory }
            }
            assertTrue(runCatching { wrongHost.open("$wrongOrigin/models").use { readBounded(it.input) } }.isFailure)
        } finally { server.stop() }
    }

    companion object {
        private const val FIXTURE = "MIIKFwIBAzCCCcUGCSqGSIb3DQEHAaCCCbYEggmyMIIJrjCCBBoGCSqGSIb3DQEHBqCCBAswggQHAgEAMIIEAAYJKoZIhvcNAQcBMF8GCSqGSIb3DQEFDTBSMDEGCSqGSIb3DQEFDDAkBBBwnGKGD3WxdTBkpMUNqjF2AgIIADAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQZA6LI5vhIQMY2WjSDCtL04CCA5DcRR32bPu32cDBSFXP9DlvfEruJguMfq3/xFhNv96Z9vOtfjk7Bsthb2qtQ9+WC/y2903gEBH1qXVk72slsyyo/IPq8Bb4A0zZAUGIW4eZVA5UCWJ9UhQCsaYEM+BVMellzGMGW8dConQu9+w3EHYa6FOC2GqSZFGi7xN8PwNiSiQKKdIuCqamVSqlcq5XVIZz8tiA8d8cZsudNqXNM2ZOc9dPW2C2Mev5d+39EDlNpwzr32Lqdh+sWdk+w1RL/TEmH2E9VE87bBlK1MyMoTyjtHUTmeg4s4yYAgtpOs7Zvjln+yF7hqMT8Jxf0APAnKpseP/TuYN4lAK48m4jCx679htlzXLYTrJUbrBLlCAxJUDjb9Yk9xpXZFEdiE1CEtx2rEews9vG7lbd+cIG2AzOgckb4FLioGZhSME09dwplY205vUF/FUGoKCMBhcxuvhquvsK8p5qahrS4pYlBEvwefl9jDoaBVtXKAzZGebJyPMesFyAbXxcUesjRMVoOa8qycoWfpnJh6AomFyMIP1jwQYSYOdAixDeGEq4/LJnIbeoxmJ4EyDSM6KC25yv5w5rTkOZjQwcd2lDyh/L18eeUz+IpM2tngWuUCTX6SMa/jTzSOivW8ISpvxcP7Bur3sm7yTer7RCHQ9Wp1C/41ch8A9ATrkVUyKxbgLxw1cOnj5ILr1q8Sl/yqUze/Wg0lsju1/WK4mgceVi+YbgxCVhPBAV+wnhTYmu+oAjgPV9WuWLekbW6az38FvdxNPgjmlcRCxPmuvuYd+ygn89woOH/IqqnjZa7/Ofrlzwvtn9p/MJxstGxBzZkS+yj8BpCi0d9rd1w3fUAw1Iu6ptP1U9Znknq/QPJVsdvyQYyRSQrU7AAxJEXIaf4oHyjG9leWM/lfAo1jLYwDKgGCn7Ilzh7RzivKpgAZtNjFgghkoS6S8EgEN+cKx7S7i0Unt6E3adjsvbg9FEMlaIAOh/jiKZgfRPCK8xEZvR+5WU6M8SWKHz97csWmSMGXlSSoQa0Yr8q7kbe+k/D4rLHM96V2H1zdRJwwPjzdsx0mkTYcqsgw8OA4TzSNNx/P/c3yykquBrVqrm6KusmjwP3lOUeL/udxZTQ+ePtecNibV7DuAieqadwCfhgDA/zQX+hphoQyqcNcjFlsT6lzDlyDO5YZpVC9Xq1ubzQBOzUxAGvem/CMu/Sb5smSxNGjxYyVOcd/IwggWMBgkqhkiG9w0BBwGgggV9BIIFeTCCBXUwggVxBgsqhkiG9w0BDAoBAqCCBTkwggU1MF8GCSqGSIb3DQEFDTBSMDEGCSqGSIb3DQEFDDAkBBB/lbS/nR6uf7ntzy0L6ZGdAgIIADAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQ5RcF/vJEYpEXDmRmeuWP9QSCBNBXP9Tkfv0tSOh03F5Bfefguq4Odij9tP5Jdee02yAYYKjsN9cEMtX44z8kKAi+NSy47HY2YwIhI/Yzi0vDWuXcaO0rLQSVNSKhtEoSuo7NEvEFuaWq8fC+qJ0FFLIeVTewExy8UfcEJCBsdDoOQK/cxPA+MCvts/Voey0jN/NGrZIxo9h3E3CH4Ff2OKVQjZ3kRgLdoo5besWaKkzLlHqkFeOnZFdd4s/YJ3L8WGyGr2id6+GDOeNJhUFXadPsps2kvMsbT2MV5ghDQXbCszcqeBdlVNlKxuvdymn9ROPtsEqvk5EOw7e5shtxYQRfgsbyF2CM+IliWNEx4enZVfWVD6DbDV8MpyAq5IZLlBPhpOib0jygO3x+AyUX1CnuGmF37tPv5+sOqtO4nxvWl5M5NxhIZ9Ji/RgCTMYV1xapiTLJT/fWYkVqoU9JrQ76XlaxFc7MekqPfNXf8Pve0VGvZ4GstWpc9Afras+aHHofCDoteeUyaduRX2LFR6UNB0OX/Tj++rEmkUZZZ3uGnFv0xUNWbZCGpFIs4mPHnPudCjIIIi5WXsUJ+XINsnf8ZW3biCbBvsY9BU44SgSuDT3dvppQ/siGvEZs95C3xujmBmMxRQEVAgbAIhqNgN0KwQjx1qP1meRD6MfzHf3rt+ttDUNc9yNiUVY7CvM2veAKvyVhwuXbuIC7IkMPgHmUQyaUPNivOcimgUVNIgeTlQV9PKGEio/OQmVHgNbfKBJPolZo8P7ISpWmFa+T99F0almdC3biKNFn+UmM/lCkTSnLtAxCymHT2Q9jut4Q6qJ3qEli54g1FQhot7SA0dw54fEQXJcFfiOZH8Wr58fGv1hP1RXMg18DGrFMdkvKSuGbsMzwi+aKtmrEYYjZIoFgWx2sLNkRT6K+KUlPvgVKX7Wd1K7TGvvVB4E0wim+/YBgsi83RvMSMaYzjxiMgQYb0Jek+41D7VLs+gdnrTnG0mLADGKVOeQv7tsmqK7kMZZlC6SwFD9PdJSe1+O39EIICOMmEsBzeLKHqxD3ar2ivoyQ/IDoBeO+yJRvNb6CL9kxkPoqZXLttejfnrh/Jo0HJtiBax3mEMS5bA4Ek7zewJveCoFJqvK0UDMOCSvdohFnwJ3WsCxcvmnChezQzCvFJMFtZMQDHvmtYyBhndmWAqhx7shUpl/S1p3TBNdC94lsIoN2dqkeNgQDKoasTnhS7fw2ip2HiukMW/pLdxVyUNJGI3d+VyTLiM/k3YvglXfDS8loVVsh/RJ006HLqfq9rbhBavGaucgIKKVavgP8NBwTF9YmeQCyXfxnXbHy/8Fp8ewjlVv8+8SmNdi44RJtqnx3Hbv8FJPqJEPSTW2pNwdRIC4mpDJsm1PTQ3rI4DxiLiiaeTq0mvkmy3CzyarjPBBkgQ/OMWpWSdY2MP/+4bXNWFKJ/mjJGxXWf5smIhrIoCMXAzP8soUGxkRslZ+BwK5WZYq8PyCufqIvM8h2zFAbWd4eyJov8viPfKMExWP+LDtxG85JB6m5MpvZ7lg7n83dD6lVG+nu7wwWz1oCc+8AQd74iGCVqfs0G77o8rCpH2PrYkqn9g+8d6BItWFTSjV4/0tsBSqUzu/GNjMQMDIWTJKYLJSozc2VNEBRIM2bwzElMCMGCSqGSIb3DQEJFTEWBBQ463ndcf6f2RctBPfz0wK7JnlBTTBJMDEwDQYJYIZIAWUDBAIBBQAEIB9OKOwrffkkRWytjHebOfVy7hRrhqbkHkFyl/VNk7IoBBDscOGoFBeOZlIfTaA/HsFyAgIIAA=="
    }
}
