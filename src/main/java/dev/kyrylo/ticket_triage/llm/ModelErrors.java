package dev.kyrylo.ticket_triage.llm;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnexpectedStatusCodeException;

/** Classifies model call failures: is it worth trying another model? */
public final class ModelErrors {

    private ModelErrors() {
    }

    /**
     * True for failures on the provider's side that another model may not have: rate limits, server errors,
     * timeouts and network errors (the SDK has already retried these). False for errors in our request,
     * such as a 400: the fallback model would get the same request and fail the same way.
     */
    public static boolean isProviderUnavailable(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof RateLimitException
                    || e instanceof InternalServerException
                    || e instanceof OpenAIIoException
                    || e instanceof OpenAIRetryableException) {
                return true;
            }
            if (e instanceof UnexpectedStatusCodeException unexpected && unexpected.statusCode() >= 500) {
                return true;
            }
        }
        return false;
    }
}
