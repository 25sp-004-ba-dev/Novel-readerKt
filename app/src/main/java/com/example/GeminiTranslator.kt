package com.example

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object GeminiTranslator {
    private const val SYSTEM_INSTRUCTION = """
        You are an expert light novel localizer translating Chinese web novels into natural, engaging English.
        You will receive a JSON array containing text blocks with their respective 'id'.
            
        Translation Rules:
        1. Translate the 'text' fields cleanly while keeping the 'id' fields identical.
        2. Maintain character name and pronoun consistency across IDs. 
        3. Localize common Chinese web novel idioms/phrases naturally (e.g., do not literally translate 'you court death' or 'coughing up blood' if it breaks flow).
        4. Output ONLY valid JSON matching the exact structure received. Do not include markdown wraps or explanations.
    """

    suspend fun translateParagraphs(
        paragraphs: List<String>,
        apiKey: String
    ): List<String> = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext emptyList()
        if (apiKey.trim().isEmpty()) {
            throw IllegalArgumentException("Gemini API key is empty.")
        }

        try {
            // 1. Construct JSON input
            val jsonInput = JSONArray()
            paragraphs.forEachIndexed { index, text ->
                val obj = JSONObject()
                obj.put("id", index)
                obj.put("text", text)
                jsonInput.put(obj)
            }
            val inputText = jsonInput.toString()

            // 2. Initialize model instance
            val model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    temperature = 0.3f
                },
                systemInstruction = com.google.ai.client.generativeai.type.content { 
                    text(SYSTEM_INSTRUCTION.trimIndent()) 
                }
            )

            // 3. Call generate content
            val response = model.generateContent(inputText)
            val responseText = response.text ?: ""
            if (responseText.isEmpty()) {
                throw Exception("Received empty response from Gemini API")
            }

            // 4. Parse JSON translation response
            val cleanResponse = try {
                var cleaned = responseText.trim()
                if (cleaned.startsWith("```json")) {
                    cleaned = cleaned.substringBeforeLast("```").substringAfter("```json")
                } else if (cleaned.startsWith("```")) {
                    cleaned = cleaned.substringBeforeLast("```").substringAfter("```")
                }
                cleaned.trim()
            } catch (e: Exception) {
                responseText
            }

            val jsonArray = JSONArray(cleanResponse)
            val translatedMap = mutableMapOf<Int, String>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getInt("id")
                val text = obj.getString("text")
                translatedMap[id] = text
            }

            // 5. Build translated paragraphs matching the original index
            val result = mutableListOf<String>()
            paragraphs.forEachIndexed { index, originalText ->
                val translatedText = translatedMap[index]
                if (translatedText != null && translatedText.isNotEmpty()) {
                    result.add(translatedText)
                } else {
                    result.add(originalText)
                }
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
