VERSION 0.8

ARG --global ALL_BUILD_TARGETS="follower-app"

ARG --global DOCKER_IMAGE_PREFIX="cf-reeve"
ARG --global DOCKER_IMAGES_EXTRA_TAGS=""
ARG --global DOCKER_REGISTRIES="docker.io/cardanofoundation"
ARG --global PUSH=false

all:
  LOCALLY
  ARG RELEASE_TAG
  FOR image_target IN $ALL_BUILD_TARGETS
    BUILD +${image_target} --RELEASE_TAG=${RELEASE_TAG}
  END

docker-publish:
  ARG EARTHLY_GIT_SHORT_HASH
  ARG RELEASE_TAG
  FOR image_target IN $ALL_BUILD_TARGETS
    BUILD +${image_target} \
      --RELEASE_TAG=${RELEASE_TAG} \
      --EARTHLY_GIT_SHORT_HASH=${EARTHLY_GIT_SHORT_HASH} \
      --DOCKER_REGISTRIES="${DOCKER_REGISTRIES}" \
      --DOCKER_IMAGES_EXTRA_TAGS="${DOCKER_IMAGES_EXTRA_TAGS}" \
      --PUSH=${PUSH}
  END

follower-app:
   ARG EARTHLY_TARGET_NAME
   ARG EARTHLY_GIT_SHORT_HASH=""
   ARG DOCKER_REGISTRIES
   ARG DOCKER_IMAGES_EXTRA_TAGS
   ARG PUSH
   
   FROM DOCKERFILE -f _backend-services/cf-reeve-ledger-follower-app/Dockerfile --target ${EARTHLY_TARGET_NAME} ./_backend-services/cf-reeve-ledger-follower-app
   
   LET IMAGE_NAME = ${DOCKER_IMAGE_PREFIX}-${EARTHLY_TARGET_NAME}
   
   # Always save local image
   SAVE IMAGE ${IMAGE_NAME}:latest
   
   # Push to registries if PUSH is enabled
   IF [ "$PUSH" = "true" ]
     FOR registry IN $DOCKER_REGISTRIES
       # Push with git short hash tag
       IF [ -n "$EARTHLY_GIT_SHORT_HASH" ]
         SAVE IMAGE --push ${registry}/${IMAGE_NAME}:${EARTHLY_GIT_SHORT_HASH}
       END
       
       # Push with extra tags
       IF [ -n "$DOCKER_IMAGES_EXTRA_TAGS" ]
         FOR image_tag IN $DOCKER_IMAGES_EXTRA_TAGS
           SAVE IMAGE --push ${registry}/${IMAGE_NAME}:${image_tag}
         END
       END
     END
   END
