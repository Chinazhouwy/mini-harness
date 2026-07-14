package com.chinazhouwy.miniharness.intent;

import com.chinazhouwy.miniharness.IntentResult;
import com.chinazhouwy.miniharness.InterviewLLMService;
import com.chinazhouwy.miniharness.Result;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClient;

public class IntentService {



    /**
     * “只做意图分类”
     */
    public static @NonNull IntentResult getIntentResult(String line, Result result) {
        IntentResult response = InterviewLLMService.createIntentClient()
                .prompt()
                .user(line)
                .messages(result.history())
                .call()
                .entity(IntentResult.class);
        return response;
    }

}
