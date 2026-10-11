package com.anchorreader.data

object SampleLesson {
    val book = Book(
        id = "anchorreader-offline-demo-v1",
        title = "How offline web caching works",
        author = "AnchorReader",
        units = listOf(
            Concept("The request comes first", listOf(
                "A browser asks a server for a page, file, or other resource.",
                "The server sends a response through the network.",
                "If the network is unavailable, the browser cannot fetch a file it has not saved.",
            )),
            Concept("A worker can choose a response", listOf(
                "A service worker is a small script that the browser can run for a page.",
                "It can receive a network request and choose which response to return.",
                "This gives the app a place to check saved lesson files before it uses the network.",
            )),
            Concept("Store request and response pairs", listOf(
                "The Cache API stores request and response pairs.",
                "A service worker can look up a saved response and return it to the page.",
                "The app must choose what to save and how to replace old copies; caching does not happen automatically.",
            )),
            Concept("Check what is ready offline", listOf(
                "Before you go offline, the app can save the lesson text, questions, diagram, and media.",
                "On a later visit, the service worker can respond from that stored set.",
                "If a needed file is missing, the app should explain that the lesson is not fully available offline.",
            )),
        ),
        sourceText = """
            The request comes first

            A browser asks a server for a page, file, or other resource. The server sends a response through the network. If the network is unavailable, the browser cannot fetch a file it has not saved.

            A worker can choose a response

            A service worker is a small script that the browser can run for a page. It can receive a network request and choose which response to return. This gives the app a place to check saved lesson files before it uses the network.

            Store request and response pairs

            The Cache API stores request and response pairs. A service worker can look up a saved response and return it to the page. The app must choose what to save and how to replace old copies; caching does not happen automatically.

            Check what is ready offline

            Before you go offline, the app can save the lesson text, questions, diagram, and media. On a later visit, the service worker can respond from that stored set. If a needed file is missing, the app should explain that the lesson is not fully available offline.
        """.trimIndent(),
        isSample = true,
        createdAt = 0,
    )

    val questions = listOf(
        RecallQuestion(1,
            "Which part can receive a request and choose a response?",
            listOf("The service worker", "The Cache API by itself", "The network connection"),
            0,
            "The service worker can receive a request and decide whether to return a saved response or use the network.",
        ),
        RecallQuestion(2,
            "What does the Cache API store?",
            listOf("Request and response pairs", "Learner accounts and passwords", "Screenshots of each page"),
            0,
            "The Cache API stores request and response pairs. The app still needs a clear plan for which files to save.",
        ),
        RecallQuestion(3,
            "What makes a lesson ready to reopen offline?",
            listOf("All files needed for the lesson are present and usable", "The app shell is saved, even if lesson media is missing", "The learner opened the lesson once while online"),
            0,
            "A complete offline lesson needs its text, questions, figures, and required media. Opening it once does not save every file.",
        ),
    )
}
