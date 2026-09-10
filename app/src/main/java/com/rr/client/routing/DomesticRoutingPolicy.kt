package com.rr.client.routing

/**
 * Small offline supplements to the maintained China rule-sets, shared by route and DNS.
 *
 * Source: v2fly/domain-list-community at [SOURCE_REVISION], data/{tencent,douyin,bytedance,
 * tiktok,alibaba,jd,pinduoduo,meituan,eleme,amap,baidu,didi,bilibili,bilibili-cdn,
 * xiaohongshu,kuaishou,zhihu,sina,netease,tencent-tme,unionpay,ctrip,google,youtube,openai,telegram,twitter}.
 * https://github.com/v2fly/domain-list-community/tree/9ff2d61d76ce28edb513920a2727f222793041c0/data
 * Additional first-party supplements use the same revision's data/{deepseek,doubao,fqnovel,
 * icbc,ccb,boc,citic,chinamobile,xiaomi-iot,huawei,tencent-games,kugou,facebook,instagram,
 * whatsapp,threads,reddit,github,xai,vk,steam,bytedance-ai-!cn}. No installed-app inventory
 * is embedded here: this is a generic service-domain policy, not a list of personal packages.
 *
 * These are domain-boundary suffixes, never substring/keyword or whole-package matches.
 * Do not add public cloud roots (myqcloud.com, aliyuncs.com, cloudfront.net, etc.), or all
 * of ByteDance's shared CDN roots. A WeChat webview/mini-program's external destination
 * must receive its own policy; a patched TikTok client needs no official package name.
 * Known international services precede broad China lists, including CN-resolved IPs.
 */
object DomesticRoutingPolicy {
    const val SOURCE_REVISION = "9ff2d61d76ce28edb513920a2727f222793041c0"

    /**
     * Narrow route exceptions observed in domestic Douyin/Fanqie/Hongguo traffic.
     * Reviewed 2026-09-08: APNIC identifies Aliyun (CN); RIPE RIS identifies AS37963
     * within 8.134.240.0/23. This is operator evidence, NOT verified physical location.
     * Keep only the three observed /32s, after international domain/package policies;
     * never expand this to the announced /23, its parent allocation, or all cloud IPs.
     * https://rdap.apnic.net/ip/8.134.241.67
     * https://stat.ripe.net/data/prefix-overview/data.json?resource=8.134.241.67
     */
    val observedMainlandIpv4Exceptions = listOf(
        "8.134.241.67/32", "8.134.241.7/32", "8.134.240.5/32"
    )

    enum class Destination { DIRECT, PROXY }

    data class DomainRule(
        val id: String,
        val destination: Destination,
        val suffixes: List<String>,
        val domains: List<String> = emptyList()
    )

    val domainRules: List<DomainRule> = listOf(
        DomainRule(
            id = "tiktok-international",
            destination = Destination.PROXY,
            suffixes = listOf(
                "bytedapm.com", "muscdn.com", "musical.ly", "tik-tokapi.com",
                "tiktok-minis.com", "tiktok-row.net", "tiktok.com", "tiktokcdn-eu.com",
                "tiktokcdn-us.com", "tiktokcdn.com", "tiktokd.net", "tiktokd.org",
                "tiktokeu-cdn.com", "tiktokglobalshopv.com", "tiktokminis.us",
                "tiktokrow-cdn.com", "tiktokv.com", "tiktokv.eu", "tiktokv.us",
                "tiktokw.eu", "tiktokw.us", "ttcdn-us.com", "ttlivecdn.com",
                "ttoverseaus.net", "ttwstatic.com",
                "tiktokcdn-us.com.edgesuite.net", "tiktokcdn.com.akamaized.net",
                "tiktokcdn.com.edgesuite.net", "tiktokv.com.edgekey.net",
                "tiktokv.com.edgesuite.net",
                // Explicit @!cn ByteDance domains, not the shared byteimg.com/snssdk.com roots.
                "byteglb.com", "byteoversea.com", "byteoversea.net", "ibytedtos.com",
                "ibyteimg.com", "capcut.com", "capcutcdn-us.com"
            ),
            domains = listOf(
                "p16-tiktokcdn-com.akamaized.net", "bytedance.map.fastly.net",
                "roovza-launches.appsflyersdk.com", "roovza.inapps.appsflyersdk.com",
                "roovza.launches.appsflyersdk.com", "roovza.skadsdk.appsflyersdk.com"
            )
        ),
        ChatGptRoutingPolicy.domainRule,
        DomainRule(
            id = "international-services",
            destination = Destination.PROXY,
            suffixes = listOf(
                "wetv.vip", "wetvinfo.com", "bilibili.tv", "biliintl.com",
                "rednote.com", "rednote.com.my", "rednotecdn.com",
                "hk.weibo.com", "my.weibo.com", "overseas.weibo.com", "sg.weibo.com",
                "th.weibo.com", "tw.weibo.com", "us.weibo.com",
                "google.com", "googleapis.com", "googleusercontent.com", "gstatic.com",
                "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com",
                "ytimg.com", "ggpht.com", "telegram.org", "telegram.me", "telegram.dog",
                "telegram-cdn.org", "cdn-telegram.org", "t.me", "telegra.ph", "telesco.pe",
                "x.com", "twitter.com", "t.co", "twimg.com", "tweetdeck.com",
                "chat.com", "sora.com", "coze.com", "marscode.com", "trae.ai",
                // International social / messaging / developer services.
                "facebook.com", "facebook.net", "fb.com", "fb.me", "fb.watch", "fbcdn.net",
                "fbsbx.com", "instagram.com", "cdninstagram.com", "ig.me", "igcdn.com",
                "whatsapp.com", "whatsapp.net", "wa.me", "threads.com", "threads.net",
                "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com", "redditspace.com",
                "github.com", "githubusercontent.com", "githubassets.com", "githubapp.com",
                "github.dev", "github.io", "ghcr.io", "grok.com", "x.ai", "grokipedia.com",
                "vk.com", "vk.ru", "vk.me", "vk.cc", "vkontakte.com", "vkontakte.ru",
                "userapi.com", "userapi.ru", "vk-cdn.net", "vk-cdn.me", "vkuseraudio.net",
                "vkuseraudio.com", "vkuserlive.com", "vkuservideo.net", "vkuservideo.com",
                // Store/community/login only: leave regional Steam download endpoints to CN/IP rules.
                "steamcommunity.com", "steampowered.com", "steamstatic.com", "steam-chat.com",
                "steam-api.com", "steamusercontent.com", "s.team"
            ),
            domains = listOf(
                "wetv.qq.com", "fbcdn-a.akamaihd.net", "reddit.map.fastly.net",
                "github-cloud.s3.amazonaws.com", "github-production-release-asset-2e65be.s3.amazonaws.com",
                "steamcommunity-a.akamaihd.net", "steamstore-a.akamaihd.net", "steammobile.akamaized.net"
            )
        ),
        DomainRule(
            id = "bigo-international",
            destination = Destination.PROXY,
            // Official service domains linked by the BIGO LIVE store listing.
            // https://play.google.com/store/apps/details?id=sg.bigo.live
            suffixes = listOf("bigo.sg", "bigo.tv"),
            domains = listOf(
                // Observed under sg.bigo.live in connection logs; domain ownership is
                // unverified. Keep exact hosts, never the shared piojm.tech/dfaklj.tech roots.
                "bglvlbs.piojm.tech", "conf-lv.piojm.tech", "support0.dfaklj.tech"
            )
        ),
        DomainRule(
            id = "wechat",
            destination = Destination.DIRECT,
            suffixes = listOf(
                // Login/messages, payment, mini-program gateway, pictures and Channels/live.
                "weixin.qq.com", "weixin.com", "wechat.com", "servicewechat.com",
                "wxgateway.com", "tenpay.com", "wechatpay.cn", "qpic.cn", "qlogo.cn",
                "wxlivecdn.com", "weixinsxy.com",
                // Short links must resolve independently from the final webview destination.
                "mmbizurl.cn", "url.cn", "wxaurl.cn", "wxmpurl.cn"
            )
        ),
        DomainRule(
            id = "douyin-mainland",
            destination = Destination.DIRECT,
            suffixes = listOf(
                "amemv.com", "amemv.cn", "amemv.net", "douyin.com", "iesdouyin.com",
                "iesdouyin.net", "douyincdn.com", "douyinpic.com", "douyinstatic.com",
                "douyinvod.com", "idouyinpic.com", "idouyinstatic.com", "idouyinvod.com",
                "douyinliving.com", "idouyinliving.com", "douyinfe.com", "douyinact.com",
                "douyinact.net", "douyinec.com", "douyinpay.com", "ulpay.com",
                "open-douyin.com", "jinritemai.com", "ecombdapi.com", "ecombdimg.com",
                "ecombdstatic.com", "ecombdvod.com", "huoshan.com", "huoshancdn.com",
                "huoshanimg.com", "huoshanlive.com", "huoshanstatic.com", "huoshanvod.com"
            ),
            domains = listOf(
                // Official Open Douyin video-data examples: covers on a shared CDN.
                // https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/server/basic-abilities/video-id-convert/user-video-data/video-data
                "p3-dy.byteimg.com",
                // blackmatrix7/ios_rule_script rule/Clash/DouYin/DouYin.list, 2025-06-06.
                "lf3-static.bytednsdoc.com", "v5-dy-o-abtest.zjcdn.com"
            )
        ),
        DomainRule(
            id = "daily-mainland-services",
            destination = Destination.DIRECT,
            suffixes = listOf(
                // First-party services and their dedicated media assets; not public cloud roots.
                "qq.com", "qqmail.com", "qzone.com", "gtimg.com", "gtimg.cn", "idqqimg.com",
                "qqmusic.com", "tencentmusic.com",
                "alipay.com", "alipayobjects.com", "taobao.com", "taobao.net", "tmall.com",
                "taobaocdn.com", "tbcdn.cn", "tb.cn", "tbcache.com", "goofish.com", "1688.com",
                "jd.com", "360buy.com", "360buyimg.com", "jdpay.com", "jdl.com",
                "pinduoduo.com", "pinduoduo.net", "yangkeduo.com", "pddpic.com", "pddcdn.com",
                "pddim.com", "pddugc.com", "meituan.com", "meituan.net", "sankuai.com",
                "dianping.com", "dpfile.com", "ele.me", "eleme.cn", "elemecdn.com", "elenet.me",
                "amap.com", "autonavi.com", "gaode.com", "baidu.com", "baidupcs.com",
                "bdimg.com", "bdstatic.com", "baidustatic.com", "tieba.com",
                "didichuxing.com", "didistatic.com", "xiaojukeji.com", "udache.com",
                "bilibili.com", "biliapi.com", "biliapi.net", "b23.tv", "acgvideo.com",
                "biliimg.com", "bilivideo.com", "bilivideo.cn", "bilivideo.net", "hdslb.com",
                "xiaohongshu.com", "xhslink.com", "xhscdn.com", "xhscdn.net", "xhsrcdn.com",
                "kuaishou.com", "kuaishou.cn", "kuaishoupay.com",
                "zhihu.com", "zhimg.com", "weibo.com", "weibo.cn", "weibocdn.com",
                "sinaimg.cn", "sinaimg.com", "sinajs.cn", "wbimg.com", "t.cn",
                "163.com", "126.com", "126.net", "127.net", "netease.com", "yeah.net",
                "95516.com", "unionpay.com", "unionpay.net", "unionpaysecure.com",
                "ctrip.com", "c-ctrip.com", "qunar.com", "qunarcdn.com", "qunarzz.com"
            )
        ),
        DomainRule(
            id = "mainland-ai-reading-music",
            destination = Destination.DIRECT,
            suffixes = listOf(
                "deepseek.com", "deepseeksvc.com", "doubao.com", "doubaocdn.com", "coze.cn",
                "qishui.com", "qishui.cn", "qishui.com.cn", "qishuimusic.cn", "qishuimusic.com.cn",
                "fanqienovel.com", "fanqieopen.com", "fanqieopenpic.com", "fanqieopenstatic.com",
                "fanqieopenvod.com", "fqnovel.com", "fqnovelpic.com", "fqnovelstatic.com",
                "fqnovelvod.com", "novelfm.com", "novelfmpic.com", "novelfmstatic.com", "novelfmvod.com",
                "kugou.com", "kugou.net", "kgimg.com", "kugouaudio.com", "kugouipv6.com"
            ),
            domains = listOf("p3-novel.byteimg.com", "p6-novel.byteimg.com")
        ),
        DomainRule(
            id = "mainland-banking-carrier-home",
            destination = Destination.DIRECT,
            suffixes = listOf(
                "95588.com", "icbc.com.cn", "ccb.com", "ccb.com.cn", "ccbcos.com",
                "boc.cn", "ecitic.com", "citicbank.com",
                // Bank-owned domain confirmed by https://www.gdnybank.com/IntDpContactUs/
                "gdnybank.com", "10086.cn", "139.com", "cmpassport.com", "cmpay.com",
                // Government identity and TP-LINK domestic platform; no global cloud-root bypass.
                // https://tyrz.gd.gov.cn/ and https://open.tp-link.com.cn/
                "gd.gov.cn", "gdzwfw.gov.cn", "tp-link.com.cn",
                "miot-spec.org", "huaweistatic.com", "dbankcdn.com",
                "qqgameapp.com", "wegame.com", "anticheatexpert.com", "17roco.com"
            ),
            domains = listOf(
                // data/xiaomi-iot identifies these actual account and domestic IoT endpoints.
                "account.xiaomi.com", "cn-ha.mqtt.io.mi.com", "ha.api.io.mi.com"
            )
        ),
        DomainRule(
            id = "verified-mainland-service-endpoints",
            destination = Destination.DIRECT,
            suffixes = emptyList(),
            domains = listOf(
                // Reviewed 2026-09-08: Shengqu's official site references these Daoyu assets.
                // https://www.sdo.com/ and https://www.sdo.com/public/js/home.js
                // https://gskd.sdoprofile.com/sq_protocol/daoyu_service.html
                "gskd.sdoprofile.com", "pics.sdoprofile.com",
                // Aliyun Queen SDK's documented request endpoint, also observed in Xianyu.
                // https://help.aliyun.com/zh/live/developer-reference/integrate-the-queen-sdk-for-wechat-mini-programs
                "vpp-license-proxy.aliyuncs.com"
            )
        ),
        // Always available offline, even before/without a downloaded China domain list.
        DomainRule("china-suffix", Destination.DIRECT, listOf("cn", "xn--fiqs8s", "xn--fiqz9s"))
    )
}
