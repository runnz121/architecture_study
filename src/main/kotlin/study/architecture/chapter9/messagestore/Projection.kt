package study.architecture.chapter9.messagestore

interface Projection<T> {
    fun init(): T
    fun apply(entity: T, event: Message): T
}
