package com.rr.client.routing

/**
 * ChatGPT's documented network dependencies, shared by route and DNS compilation.
 * https://help.openai.com/zh-hans-cn/articles/9247338-network-recommendations-for-chatgpt-errors-on-web-and-apps
 *
 * Keep wildcard entries at their documented domain boundaries and shared providers at
 * exact host scope. In particular, never proxy every Cloudflare, WorkOS, Stripe, Sentry,
 * SendGrid or public-cloud tenant just because ChatGPT uses one endpoint there.
 * This is routing only: TLS certificates and end-to-end WebSocket/QUIC traffic are not
 * decrypted or rewritten. Voice IP ranges remain upstream-maintained, not pinned here.
 */
object ChatGptRoutingPolicy {
    const val PACKAGE_NAME = "com.openai.chatgpt"
    const val RULE_ID = "chatgpt-services"

    val domainRule = DomesticRoutingPolicy.DomainRule(
        id = RULE_ID,
        destination = DomesticRoutingPolicy.Destination.PROXY,
        suffixes = listOf(
            // auth.openai.com and all other documented OpenAI subdomains are covered.
            "chatgpt.com", "openai.com", "oaistatic.com", "oaiusercontent.com",
            "oaistatsig.com", "ct.sendgrid.net", "intercom.io", "intercomcdn.com"
        ),
        domains = listOf(
            // Retain the article's exact endpoints for reviewability, even where a
            // first-party suffix above already covers them. ws is its WebSocket host.
            "android.chat.openai.com", "auth0.openai.com", "cdn.openaimerge.com",
            "cdn.workos.com", "challenges.cloudflare.com", "chat.openai.com",
            "desktop.chat.openai.com", "forwarder.workos.com", "humb.apple.com",
            "images.workoscdn.com", "ios.chat.openai.com", "js.intercomcdn.com",
            "js.stripe.com", "o207216.ingest.sentry.io", "o33249.ingest.sentry.io",
            "rum.browser-intake-datadoghq.com", "setup.auth.openai.com", "setup.workos.com",
            "tcr9i.chat.openai.com", "workos.imgix.net", "ws.chatgpt.com"
        )
    )
}
