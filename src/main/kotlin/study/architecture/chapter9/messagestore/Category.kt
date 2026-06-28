package study.architecture.chapter9.messagestore

/**
 * Chapter 9 §8: Message Store의 Origin Stream 인식
 *
 * 스트림 이름 `identity-88513bc7-...`에서 `-` 앞부분인 `identity`가 카테고리이다.
 * subscription의 originStreamName과 메시지 metadata의 originStreamName 카테고리를
 * 비교하여 필터링하는 데 사용된다.
 */
fun category(streamName: String?): String {
    if (streamName.isNullOrEmpty()) return ""
    return streamName.substringBefore('-')
}

/**
 * 스트림 이름에서 id 부분(첫 `-` 뒤 전체)을 추출한다.
 * 예: `identity-88513bc7-...` -> `88513bc7-...`
 * id 자체가 UUID라서 `-`를 포함하므로 첫 번째 `-`만 기준으로 자른다.
 */
fun streamNameToId(streamName: String?): String {
    if (streamName.isNullOrEmpty()) return ""
    return streamName.substringAfter('-', "")
}
