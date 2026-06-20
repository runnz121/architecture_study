package study.architecture.chapter8.messagestore

interface Projection<T> {
    fun init(): T
    fun apply(entity: T, event: Message): T
}
