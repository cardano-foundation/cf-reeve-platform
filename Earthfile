VERSION 0.8

ARG --global ALL_BUILD_TARGETS="platform-library-m2-cache follower-app"

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
  ARG GITHUB_EVENT_NAME
  LOCALLY
  LET PUSH_PUBLIC = "false"
  IF [ ! "$GITHUB_EVENT_NAME" = "pull_request" ]
    SET PUSH_PUBLIC = "true"
  END
  RUN echo "push public is set to $PUSH_PUBLIC"
  WAIT
    BUILD +all --RELEASE_TAG=${RELEASE_TAG}
  END
  LOCALLY
  LET IMAGE_NAME = ""
  LET PUBLIC = "false"
  LET PUSH_THIS_REPO = "$PUSH"
  LET REGISTRY = ""
  FOR registry IN $DOCKER_REGISTRIES
    FOR image_target IN $ALL_BUILD_TARGETS
      SET IMAGE_NAME = ${DOCKER_IMAGE_PREFIX}-${image_target}
      SET PUBLIC = "false"
      SET REGISTRY = "$registry"
      # TODO: include "docker.io/cardanofoundation" in github secret and remove this exception
      IF [ "$registry" = "hub.docker.com" ] 
        SET PUBLIC = "true"
        SET REGISTRY = "docker.io/cardanofoundation"
      END
      IF [ "$registry" = "docker.io/cardanofoundation" ] 
        SET PUBLIC = "true"
      END
      IF [ "$registry" = "ghcr.io/cardano-foundation" ] 
        SET PUBLIC = "true"
      END
      SET PUSH_THIS_REPO = "$PUSH"
      IF [ "$PUBLIC" = "true" ]
        IF [ "$PUSH_PUBLIC" = "false" ]
          SET PUSH_THIS_REPO = "false"
        END
      END

      IF [ "$PUSH_THIS_REPO" = "true" ]
        IF [ ! -z "$DOCKER_IMAGES_EXTRA_TAGS" ]
          FOR image_tag IN $DOCKER_IMAGES_EXTRA_TAGS
            RUN docker tag ${IMAGE_NAME}:latest ${REGISTRY}/${IMAGE_NAME}:${image_tag}
            RUN docker push ${REGISTRY}/${IMAGE_NAME}:${image_tag}
          END
        END
        RUN docker tag ${IMAGE_NAME}:latest ${REGISTRY}/${IMAGE_NAME}:${EARTHLY_GIT_SHORT_HASH}
        RUN docker push ${REGISTRY}/${IMAGE_NAME}:${EARTHLY_GIT_SHORT_HASH}
      END

    END
  END

platform-library-m2-cache:
  ARG EARTHLY_TARGET_NAME
  FROM DOCKERFILE -f Dockerfile --target ${EARTHLY_TARGET_NAME} .
  SAVE IMAGE ${DOCKER_IMAGE_PREFIX}-${EARTHLY_TARGET_NAME}:latest

follower-app:
   ARG EARTHLY_TARGET_NAME
   FROM DOCKERFILE -f _backend-services/cf-reeve-ledger-follower-app/Dockerfile --target ${EARTHLY_TARGET_NAME} ./_backend-services/cf-reeve-ledger-follower-app
   SAVE IMAGE ${DOCKER_IMAGE_PREFIX}-${EARTHLY_TARGET_NAME}:latest
