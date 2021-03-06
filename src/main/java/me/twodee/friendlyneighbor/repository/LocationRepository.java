package me.twodee.friendlyneighbor.repository;

import me.twodee.friendlyneighbor.entity.UserLocation;
import me.twodee.friendlyneighbor.exception.DbFailure;
import me.twodee.friendlyneighbor.exception.InvalidUser;

import java.util.List;

public interface LocationRepository
{
    /**
     * Saves a user's userLocation data
     * @param userLocation User data with location/radius information in KM
     * @return Returns the persisted user location
     */
    UserLocation save(UserLocation userLocation);

    /**
     * Look up users who are near a given user who is already registered
     *
     * @param userId The user looking up nearby users
     * @return A list of users with their distance
     */
    List<UserLocation> getUsersNearBy(String userId) throws InvalidUser, DbFailure;

    /**
     * Look up users who are near a given location
     *
     * @param userLocation A user location object
     * @return A list of users with their distance
     */
    List<UserLocation> getUsersNearBy(UserLocation userLocation) throws InvalidUser, DbFailure;

    /**
     * Get a single user's location details
     *
     * @param id User ID string
     * @return Location details of said user
     */
    UserLocation findById(String id);

    /**
     * Delete a user location based on their id
     *
     * @param id user's identifier
     */
    void deleteById(String id);
}
