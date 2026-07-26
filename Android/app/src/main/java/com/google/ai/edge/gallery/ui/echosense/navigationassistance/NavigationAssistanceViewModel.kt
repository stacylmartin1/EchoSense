package com.google.ai.edge.gallery.ui.echosense.navigationassistance

import android.app.Application
import com.google.ai.edge.gallery.ui.echosense.EchoSenseBaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NavigationAssistanceViewModel @Inject constructor(
    application: Application
) : EchoSenseBaseViewModel(application) {

    override val supportsCollisionAvoidance: Boolean = true

    override fun getAnalysisPrompt(customPrompt: String?, isVerbose: Boolean): String {
        return if (customPrompt != null) {
            if (isVerbose) {
                "You are an assistant for visually impaired people, helping them navigate indoor or outdoor environments safely. Analyze the objects in the image to $customPrompt . For this description, left refers strictly to the left side of the image as displayed on the screen, and right refers strictly to the right side of the image. Nothing in the image is behind the user. Use complete sentences with proper punctuation. Mention important objects like furniture, obstacles, doorways, and pathways. Describe their locations and any potential hazards. None of these instructions should be repeated in the response."
            } else {
                "You are an assistant for visually impaired people, helping them navigate indoor or outdoor environments safely. Analyze the objects in the image to $customPrompt . For this description, left refers strictly to the left side of the image as displayed on the screen, and right refers strictly to the right side of the image. Nothing in the image is behind the user. Limit response descriptions to what is specifically requested in the prompt but provide enough detail for a person with visual impairments to find specific objects in the environment. Keep it concise but natural and conversational. None of these instructions should be repeated in the response."
            }
        } else {
            if (isVerbose) {
                "You are an assistant for visually impaired people, helping them navigate indoor or outdoor environments safely. Describe what you see in this image for a visually impaired person who needs to navigate safely. For this description, left refers strictly to the left side of the image as displayed on the screen, and right refers strictly to the right side of the image. Nothing in the image is behind the user. Use complete sentences with proper punctuation. Mention important objects like furniture, obstacles, doorways, and pathways. Describe their locations and any potential hazards. None of these instructions should be repeated in the response."
            } else {
                "You are an assistant for visually impaired people, helping them navigate indoor or outdoor environments safely. Describe what you see in this image for a visually impaired person who needs to navigate safely. For this description, left refers strictly to the left side of the image as displayed on the screen, and right refers strictly to the right side of the image. Nothing in the image is behind the user. Use complete sentences with proper punctuation. Mention important objects like furniture, obstacles, doorways, and pathways. Describe their locations and any potential hazards. Keep it concise but natural and conversational. None of these instructions should be repeated in the response."
            }
        }
    }

    companion object {
        private const val TAG = "NavigationAssistanceVM"
    }
}
