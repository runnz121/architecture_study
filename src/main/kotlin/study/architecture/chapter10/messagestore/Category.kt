package study.architecture.chapter10.messagestore

/**
 * 스트림 이름에서 카테고리(첫 `-` 앞부분)를 추출한다.
 * 예: `videoPublishing-9bfb5f98-...` -> `videoPublishing`
 */
fun category(streamName: String?): String {
    if (streamName.isNullOrEmpty()) return ""
    return streamName.substringBefore('-')
}

/**
 * 스트림 이름에서 id 부분(첫 `-` 뒤 전체)을 추출한다.
 * id 자체가 UUID라 `-`를 포함하므로 첫 번째 `-`만 기준으로 자른다.
 */
fun streamNameToId(streamName: String?): String {
    if (streamName.isNullOrEmpty()) return ""
    return streamName.substringAfter('-', "")
}
