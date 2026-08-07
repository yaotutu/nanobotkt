package com.nanobotkt.feature.chat

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class ChatModule {
    @Binds abstract fun bindChatRepository(implementation: DefaultChatRepository): ChatRepository
    @Binds abstract fun bindVoiceRecorder(implementation: NativeVoiceRecorder): VoiceRecorder
    @Binds abstract fun bindAttachmentEncoding(implementation: AttachmentEncoder): AttachmentEncoding
}
