package logic_core.infrastructure.transport.http;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorStatusMapperTest
{
    @Test
    void nullErrorCode_mapsTo200()
    {
        assertThat(ErrorStatusMapper.statusFor(null))
                .isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void clientInputErrors_mapTo400()
    {
        assertThat(ErrorStatusMapper.statusFor("MALFORMED_JSON"))
                .isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(ErrorStatusMapper.statusFor("VALIDATION_ERROR"))
                .isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(ErrorStatusMapper.statusFor("UNKNOWN_REQUEST"))
                .isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
    }

    @Test
    void authenticationCodes_mapTo401()
    {
        assertThat(ErrorStatusMapper.statusFor("AUTH_REQUIRED"))
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(ErrorStatusMapper.statusFor("UNAUTHORIZED"))
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void forbidden_mapsTo403()
    {
        assertThat(ErrorStatusMapper.statusFor("FORBIDDEN"))
                .isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void notFound_mapsTo404()
    {
        assertThat(ErrorStatusMapper.statusFor("NOT_FOUND"))
                .isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void conflict_mapsTo409()
    {
        assertThat(ErrorStatusMapper.statusFor("CONFLICT"))
                .isEqualTo(HttpServletResponse.SC_CONFLICT);
    }

    @Test
    void unsupportedMediaType_mapsTo415()
    {
        assertThat(ErrorStatusMapper.statusFor("UNSUPPORTED_MEDIA_TYPE"))
                .isEqualTo(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void serverErrors_mapTo500()
    {
        assertThat(ErrorStatusMapper.statusFor("DATABASE_ERROR"))
                .isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertThat(ErrorStatusMapper.statusFor("UNEXPECTED_ERROR"))
                .isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }

    @Test
    void routeSpecificBusinessFailures_remain200()
    {
        assertThat(ErrorStatusMapper.statusFor("TWEET_LIKE_FAILED"))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(ErrorStatusMapper.statusFor("TWEET_GET_FAILED"))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(ErrorStatusMapper.statusFor("AUTH_LOGIN_FAILED"))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(ErrorStatusMapper.statusFor("GET_PROFILE_FAILED"))
                .isEqualTo(HttpServletResponse.SC_OK);
        assertThat(ErrorStatusMapper.statusFor("NOTIFICATION_READ_FAILED"))
                .isEqualTo(HttpServletResponse.SC_OK);
    }
}