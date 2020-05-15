package me.twodee.friendlyneighbor.service;

import lombok.extern.java.Log;
import me.twodee.friendlyneighbor.FnCoreGenerated;
import me.twodee.friendlyneighbor.dto.Notification;
import me.twodee.friendlyneighbor.dto.ResultObject;
import me.twodee.friendlyneighbor.dto.UserLocationsResult;
import me.twodee.friendlyneighbor.entity.UserLocation;
import me.twodee.friendlyneighbor.exception.InvalidUser;
import me.twodee.friendlyneighbor.repository.LocationRepository;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Log
public class Discovery
{
    private final LocationRepository repository;

    @Inject
    public Discovery(LocationRepository repository)
    {
        this.repository = repository;
    }

    public ResultObject saveUserLocation(UserLocation location)
    {
        try {
            repository.save(location);
            return new ResultObject();
        } catch (Throwable e) {
            return somethingWentWrong(e);
        }
    }

    public ResultObject deleteUserLocation(String id)
    {
        try {
            repository.deleteById(id);
            return new ResultObject();
        } catch (Throwable e) {
            return somethingWentWrong(e);
        }

    }

    public UserLocationsResult lookupNearbyUsersByUserId(String requestingUid)
    {
        try {
            return new UserLocationsResult(repository.getUsersNearBy(requestingUid));
        } catch (InvalidUser e) {
            return buildErrorDTO(e);
        }
    }

    public UserLocationsResult lookupNearbyUsersByLocation(UserLocation location)
    {
        try {
            return new UserLocationsResult(repository.getUsersNearBy(location));
        } catch (InvalidUser e) {
            return buildErrorDTO(e);
        }
    }

    private ResultObject somethingWentWrong(Throwable e)
    {
        log.severe(Arrays.toString(e.getStackTrace()));
        return new ResultObject("internal", ResultObject.SOMETHING_WENT_WRONG);
    }

    private UserLocationsResult buildErrorDTO(Throwable e)
    {
        Notification note = new Notification();
        if (e instanceof InvalidUser) {
            note.addError("userId", "The supplied User ID doesn't exist");
        }
        return new UserLocationsResult(note);
    }

    /**
     * @param request
     * @return
     * @deprecated
     */
    public FnCoreGenerated.RequestResult saveUserLocation(FnCoreGenerated.RegistrationRequest request)
    {
        UserLocation userLocation = new UserLocation(request.getUserId(),
                                                     new UserLocation.Position(request.getLocation().getLatitude(),
                                                                               request.getLocation().getLongitude()),
                                                     request.getRadius());
        if (repository.save(userLocation).equals(userLocation)) {
            return FnCoreGenerated.RequestResult.newBuilder().setSuccess(true).build();
        }
        return FnCoreGenerated.RequestResult.newBuilder().setSuccess(false).build();
    }

    /**
     * @param request
     * @return
     * @deprecated
     */
    public FnCoreGenerated.RequestResult deleteUser(FnCoreGenerated.UserIdentifier request)
    {
        repository.deleteById(request.getUserId());
        return FnCoreGenerated.RequestResult.newBuilder().setSuccess(true).build();
    }

    /**
     * @param location
     * @return
     * @deprecated
     */
    public FnCoreGenerated.NearbyUsersResult lookupNearbyUsersByLocation(FnCoreGenerated.SearchAreaRequest location)
    {
        try {
            UserLocation userLocation = new UserLocation(location.getUserId(),
                                                         new UserLocation.Position(location.getLocation().getLatitude(),
                                                                                   location.getLocation().getLongitude()),
                                                         location.getRadius());
            List<FnCoreGenerated.UserNearby> users = buildUserList(repository.getUsersNearBy(userLocation));
            return buildResult(users);

        } catch (InvalidUser e) {
            return buildFailedResult(e);
        }
    }

    /**
     * @param identifier
     * @return
     * @deprecated
     */
    public FnCoreGenerated.NearbyUsersResult lookupNearbyUsersByUserId(FnCoreGenerated.UserIdentifier identifier)
    {
        try {
            List<FnCoreGenerated.UserNearby> users = buildUserList(repository.getUsersNearBy(identifier.getUserId()));
            return buildResult(users);

        } catch (InvalidUser e) {
            return FnCoreGenerated.NearbyUsersResult
                    .newBuilder()
                    .setMetaResult(FnCoreGenerated.RequestResult.newBuilder()
                                           .setSuccess(false)
                                           .putAllErrors(getErrors(e))
                                           .build()).build();
        }
    }

    private FnCoreGenerated.NearbyUsersResult buildFailedResult(Throwable e)
    {
        return FnCoreGenerated.NearbyUsersResult
                .newBuilder()
                .setMetaResult(FnCoreGenerated.RequestResult.newBuilder()
                                       .setSuccess(false)
                                       .putAllErrors(getErrors(e))
                                       .build()).build();
    }

    Map<String, String> getErrors(Throwable e)
    {
        Map<String, String> errors = new HashMap<>();
        if (e instanceof InvalidUser) {
            errors.put("userId", "The supplied User ID doesn't exist");
        }
        return errors;
    }

    private FnCoreGenerated.UserNearby createDtoUser(UserLocation userLocation)
    {
        return FnCoreGenerated.UserNearby.newBuilder()
                .setDistance(userLocation.getDistance().doubleValue())
                .setUserId(userLocation.getId())
                .build();
    }

    private FnCoreGenerated.NearbyUsersResult buildResult(List<FnCoreGenerated.UserNearby> results)
    {
        return FnCoreGenerated.NearbyUsersResult.newBuilder()
                .setMetaResult(FnCoreGenerated.RequestResult.newBuilder().setSuccess(true).build())
                .addAllUser(results)
                .build();
    }

    private List<FnCoreGenerated.UserNearby> buildUserList(List<UserLocation> users)
    {
        return users.stream()
                .map(this::createDtoUser)
                .collect(Collectors.toList());
    }
}
